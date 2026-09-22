package dev.forgetme;

import java.net.http.HttpClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Moves verified requests through the connectors, stage by stage.
 * Every few seconds {@link #tick()} starts requests whose cooling-off is over and sends any task that is due.
 */
@Service
public class Dispatcher {

    public static final int MAX_ATTEMPTS = 5;
    private static final int BATCH = 10;
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    /** What a connector receives. */
    record ErasePayload(UUID taskId, UUID requestId, String email, String callbackUrl) {}

    private final PrivacyRequestRepository requests;
    private final ConnectorRepository connectors;
    private final TaskRepository tasks;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Crypto crypto;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final Duration retryBackoff;
    private final Duration callbackTimeout;
    private final String baseUrl;
    private final RestClient http;

    public Dispatcher(PrivacyRequestRepository requests, ConnectorRepository connectors, TaskRepository tasks,
                      JdbcClient jdbc, TransactionTemplate tx, Crypto crypto, AuditLog audit, ObjectMapper json,
                      @Value("${forgetme.retry-backoff}") Duration retryBackoff,
                      @Value("${forgetme.callback-timeout}") Duration callbackTimeout,
                      @Value("${forgetme.base-url}") String baseUrl) {
        this.requests = requests;
        this.connectors = connectors;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.tx = tx;
        this.crypto = crypto;
        this.audit = audit;
        this.json = json;
        this.retryBackoff = retryBackoff;
        this.callbackTimeout = callbackTimeout;
        this.baseUrl = baseUrl;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${forgetme.tick}", initialDelayString = "${forgetme.tick}")
    public void tick() {
        Instant now = Instant.now();
        requests.findIdsWithExpiredCode(now).forEach(id -> withLockedRequest(id, r -> {
            if (r.getStatus() == RequestStatus.RECEIVED) audit.move(r, RequestStatus.REJECTED, "code never confirmed");
        }));
        requests.findIdsReadyToStart(now).forEach(id -> withLockedRequest(id, r -> {
            if (r.getStatus() != RequestStatus.WAITING) return; // cancelled a moment ago
            audit.move(r, RequestStatus.RUNNING, "cooling-off over");
            log.info("Request {}: cooling-off over, starting", id);
            advance(r);
        }));
        // ponytail: one thread sends tasks one after another; send in parallel (virtual threads) if connectors get slow.
        List<UUID> claimed;
        do {
            claimed = claimDueTasks();
            claimed.forEach(this::send);
        } while (claimed.size() == BATCH);
    }

    /** A connector reporting back. Safe to repeat: a finished task ignores duplicates. */
    public void recordResult(UUID taskId, Task.Result result, String note) {
        withLockedTask(taskId, (request, task) -> {
            if (task.getStatus() == Task.Status.DONE) return;
            task.done(result, note);
            audit.record(request.getId(), "TASK_DONE", nameOf(task) + ": " + result + (note == null ? "" : " (" + note + ")"));
            log.info("Task {}: connector reported {}", taskId, result);
            advance(request);
        });
    }

    /** Admin button for a request stuck in NEEDS_ATTENTION: give its failed tasks a fresh set of attempts. */
    public PrivacyRequest retry(UUID requestId) {
        return tx.execute(s -> {
            PrivacyRequest r = requests.findLockedById(requestId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (r.getStatus() != RequestStatus.NEEDS_ATTENTION) {
                throw new RequestStatus.IllegalTransition(r.getStatus(), RequestStatus.RUNNING);
            }
            audit.move(r, RequestStatus.RUNNING, "admin retry");
            tasks.findByRequestId(requestId).stream()
                    .filter(t -> t.getStatus() == Task.Status.FAILED)
                    .forEach(Task::retryNow);
            return r;
        });
    }

    /**
     * Claims up to BATCH due tasks in one short transaction. FOR UPDATE SKIP LOCKED means two dispatchers never grab
     * the same task, and pushing next_attempt_at out by a lease means the lock isn't held during the slow HTTP call.
     * If we crash mid-send, the lease runs out and the task is picked up again.
     */
    private List<UUID> claimDueTasks() {
        Instant now = Instant.now();
        return tx.execute(s -> jdbc.sql("""
                        update task set attempts = attempts + 1, next_attempt_at = :leaseEnd
                        where id in (select id from task
                                     where status in ('PENDING', 'SENT') and next_attempt_at <= :now
                                     order by next_attempt_at
                                     limit :batch
                                     for update skip locked)
                        returning id""")
                .param("now", Timestamp.from(now))
                .param("leaseEnd", Timestamp.from(now.plus(LEASE)))
                .param("batch", BATCH)
                .query(UUID.class).list());
    }

    private void send(UUID taskId) {
        Task task = tasks.findById(taskId).orElseThrow();
        if (task.getAttempts() > MAX_ATTEMPTS) { // was SENT, never confirmed, and out of attempts
            recordError(taskId, "No confirmation after " + MAX_ATTEMPTS + " attempts");
            return;
        }
        Connector connector = connectors.findById(task.getConnectorId()).orElseThrow();
        PrivacyRequest request = requests.findById(task.getRequestId()).orElseThrow();
        String body = json.writeValueAsString(new ErasePayload(taskId, request.getId(),
                crypto.decrypt(request.getEmailEnc()), baseUrl + "/api/callbacks/" + taskId));
        long timestamp = Instant.now().getEpochSecond();
        try {
            http.post().uri(connector.getEndpointUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(Crypto.TIMESTAMP_HEADER, String.valueOf(timestamp))
                    .header(Crypto.SIGNATURE_HEADER, Crypto.sign(crypto.decrypt(connector.getSecretEnc()), timestamp, body))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity(); // anything but 2xx throws
            recordSent(taskId);
            log.info("Task {}: sent to {} (attempt {})", taskId, connector.getName(), task.getAttempts());
        } catch (RestClientException e) {
            log.warn("Task {}: {} failed (attempt {}): {}", taskId, connector.getName(), task.getAttempts(), e.getMessage());
            recordError(taskId, e.getMessage());
        }
    }

    private void recordSent(UUID taskId) {
        tx.executeWithoutResult(s -> {
            Task task = tasks.findLockedById(taskId).orElseThrow();
            if (task.isOpen()) task.sent(Instant.now().plus(callbackTimeout)); // a fast connector may already be DONE
        });
    }

    private void recordError(UUID taskId, String error) {
        withLockedTask(taskId, (request, task) -> {
            if (!task.isOpen()) return; // a callback finished it meanwhile
            if (task.getAttempts() >= MAX_ATTEMPTS) {
                task.fail(error);
                audit.record(request.getId(), "TASK_FAILED", nameOf(task) + ": no success after " + MAX_ATTEMPTS + " attempts");
                log.warn("Task {}: out of attempts", taskId);
                advance(request);
            } else {
                // Wait base, 2x base, 4x base... so a struggling connector isn't hammered.
                task.retryAt(Instant.now().plus(retryBackoff.multipliedBy(1L << (task.getAttempts() - 1))), error);
            }
        });
    }

    /**
     * Decides what happens next for a request after any task changes. Must run with the request row locked:
     * otherwise two callbacks finishing the last two tasks at the same moment could each think the other is still
     * open, and the request would never move on.
     */
    private void advance(PrivacyRequest request) {
        List<Task> all = tasks.findByRequestId(request.getId());
        if (all.stream().anyMatch(t -> t.getStatus() == Task.Status.FAILED)) {
            if (request.getStatus() == RequestStatus.RUNNING) {
                audit.move(request, RequestStatus.NEEDS_ATTENTION, "a system ran out of attempts");
            }
            return;
        }
        if (request.getStatus() == RequestStatus.NEEDS_ATTENTION) {
            audit.move(request, RequestStatus.RUNNING, "a late report fixed the last failure");
        }
        if (all.stream().anyMatch(t -> t.getStatus() != Task.Status.DONE)) return; // current stage still going

        int currentStage = all.stream().mapToInt(Task::getStage).max().orElse(0);
        Integer nextStage = connectors.findNextStage(currentStage);
        if (nextStage == null) {
            audit.move(request, RequestStatus.COMPLETED, "every system reported back; ForgetMe's copy of the email erased");
            log.info("Request {}: completed", request.getId());
            return;
        }
        List<Connector> stage = connectors.findByStage(nextStage);
        stage.forEach(c -> tasks.save(new Task(request.getId(), c.getId(), nextStage)));
        audit.record(request.getId(), "STAGE_STARTED", "stage " + nextStage + ": "
                + String.join(", ", stage.stream().map(Connector::getName).toList()));
        log.info("Request {}: stage {} started", request.getId(), nextStage);
    }

    private String nameOf(Task task) {
        return connectors.findById(task.getConnectorId()).map(Connector::getName).orElse("?");
    }

    private void withLockedRequest(UUID requestId, Consumer<PrivacyRequest> action) {
        tx.executeWithoutResult(s -> action.accept(requests.findLockedById(requestId).orElseThrow()));
    }

    /** Always locks the request first, then the task. One fixed order means two threads can't deadlock. */
    private void withLockedTask(UUID taskId, BiConsumer<PrivacyRequest, Task> action) {
        UUID requestId = tasks.findById(taskId).orElseThrow().getRequestId();
        withLockedRequest(requestId, r -> action.accept(r, tasks.findLockedById(taskId).orElseThrow()));
    }
}
