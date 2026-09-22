package dev.forgetme;

import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Admin only: the receipt for a finished request, and a check that the audit log hasn't been tampered with. */
@RestController
class ProofController {

    static final String STATEMENT = "Every registered system reported back on this request. When it completed, "
            + "ForgetMe erased its own copy of the requester's email; only the keyed fingerprint above remains. "
            + "Keep the audit hash: it lets anyone check later that this history hasn't been changed.";

    record SystemResult(String system, int stage, Task.Result result, String note, Instant reportedAt) {}

    record HistoryEntry(Instant at, String event, String detail) {}

    record Certificate(UUID requestId, String subjectFingerprint, Instant receivedAt, Instant dueAt,
                       Instant completedAt, boolean onTime, List<SystemResult> systems, List<HistoryEntry> history,
                       String auditHash, String statement) {}

    private final PrivacyRequestRepository requests;
    private final TaskRepository tasks;
    private final ConnectorRepository connectors;
    private final AuditLog audit;

    ProofController(PrivacyRequestRepository requests, TaskRepository tasks, ConnectorRepository connectors, AuditLog audit) {
        this.requests = requests;
        this.tasks = tasks;
        this.connectors = connectors;
        this.audit = audit;
    }

    @GetMapping("/api/requests/{id}/certificate")
    Certificate certificate(@PathVariable UUID id) {
        PrivacyRequest r = requests.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (r.getStatus() != RequestStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request is " + r.getStatus() + ". A certificate is issued once it's COMPLETED.");
        }
        Map<UUID, String> names = connectors.findAll().stream().collect(Collectors.toMap(Connector::getId, Connector::getName));
        List<SystemResult> systems = tasks.findByRequestId(id).stream()
                .map(t -> new SystemResult(names.get(t.getConnectorId()), t.getStage(), t.getResult(), t.getNote(), t.getUpdatedAt()))
                .sorted(Comparator.comparing(SystemResult::stage).thenComparing(SystemResult::system))
                .toList();
        List<AuditLog.Event> events = audit.history(id);
        return new Certificate(id, hex(r.getSubjectHash()), r.getReceivedAt(), r.getDueAt(), r.getClosedAt(),
                !r.getClosedAt().isAfter(r.getDueAt()), systems,
                events.stream().map(e -> new HistoryEntry(e.at(), e.event(), e.detail())).toList(),
                events.isEmpty() ? null : hex(events.getLast().hash()), // empty only for requests older than the audit log
                STATEMENT);
    }

    @GetMapping("/api/audit/verify")
    AuditLog.Check verifyAuditLog() {
        return audit.verify();
    }

    private static String hex(byte[] bytes) {
        return bytes == null ? null : HexFormat.of().formatHex(bytes);
    }
}
