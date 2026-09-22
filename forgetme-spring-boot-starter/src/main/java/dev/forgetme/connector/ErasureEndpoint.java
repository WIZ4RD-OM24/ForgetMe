package dev.forgetme.connector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The door ForgetMe knocks on: POST /privacy/erase.
 * Checks the stamp, runs the app's ErasureHandler, answers 202, then reports the result back to ForgetMe.
 */
@RestController
public class ErasureEndpoint {

    /** What ForgetMe sends. */
    record Job(UUID taskId, UUID requestId, String email, String callbackUrl) {}

    private static final Logger log = LoggerFactory.getLogger(ErasureEndpoint.class);

    private final ErasureHandler handler;
    private final String secret;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ErasureEndpoint(ErasureHandler handler, String secret, ObjectMapper json) {
        this.handler = handler;
        this.secret = secret;
        this.json = json;
    }

    /**
     * Runs the handler straight away, so a failure can be answered with 500 and ForgetMe retries within seconds.
     * On success, answers 202 and sends the signed report from a separate thread.
     */
    @PostMapping("/privacy/erase")
    ResponseEntity<Void> erase(@RequestHeader(Signatures.TIMESTAMP_HEADER) long timestamp,
                               @RequestHeader(Signatures.SIGNATURE_HEADER) String signature,
                               @RequestBody String body) {
        if (!Signatures.verify(secret, timestamp, body, signature)) {
            log.warn("Rejected an erasure job with a bad or old signature");
            return ResponseEntity.status(401).build();
        }
        Job job = json.readValue(body, Job.class);
        ErasureResult result;
        try {
            result = handler.erase(new ErasureHandler.Subject(job.requestId(), job.email()));
        } catch (Exception e) {
            log.warn("Task {}: erasure failed, ForgetMe will retry: {}", job.taskId(), e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
        Thread.startVirtualThread(() -> report(job, result));
        return ResponseEntity.accepted().build();
    }

    /** If this fails, nothing is lost: ForgetMe sends the job again when the report doesn't arrive. */
    private void report(Job job, ErasureResult result) {
        String body = json.writeValueAsString(result);
        long timestamp = Instant.now().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create(job.callbackUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(Signatures.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .header(Signatures.SIGNATURE_HEADER, Signatures.sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 204) log.info("Task {}: reported {}", job.taskId(), result.result());
            else log.warn("Task {}: ForgetMe answered {} to our report", job.taskId(), status);
        } catch (Exception e) {
            log.warn("Task {}: couldn't report ({}); ForgetMe will send the job again", job.taskId(), e.getMessage());
        }
    }
}
