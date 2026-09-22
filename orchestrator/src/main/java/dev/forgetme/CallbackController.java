package dev.forgetme;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Where connectors report "done". No login here: each message must carry a valid signature made with that
 * connector's secret instead.
 */
@RestController
class CallbackController {

    record Report(Task.Result result, String note) {}

    private final TaskRepository tasks;
    private final ConnectorRepository connectors;
    private final Dispatcher dispatcher;
    private final Crypto crypto;
    private final ObjectMapper json;

    CallbackController(TaskRepository tasks, ConnectorRepository connectors, Dispatcher dispatcher,
                       Crypto crypto, ObjectMapper json) {
        this.tasks = tasks;
        this.connectors = connectors;
        this.dispatcher = dispatcher;
        this.crypto = crypto;
        this.json = json;
    }

    /** Takes the raw body as text: the signature covers the exact bytes sent, so we check it before parsing. */
    @PostMapping("/api/callbacks/{taskId}")
    ResponseEntity<Void> report(@PathVariable UUID taskId,
                                @RequestHeader(Crypto.TIMESTAMP_HEADER) long timestamp,
                                @RequestHeader(Crypto.SIGNATURE_HEADER) String signature,
                                @RequestBody String body) {
        Task task = tasks.findById(taskId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        Connector connector = connectors.findById(task.getConnectorId()).orElseThrow();
        if (!Crypto.verify(crypto.decrypt(connector.getSecretEnc()), timestamp, body, signature)) {
            return ResponseEntity.status(401).build();
        }
        Report report;
        try {
            report = json.readValue(body, Report.class);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().build();
        }
        if (report.result() == null) return ResponseEntity.badRequest().build();
        dispatcher.recordResult(taskId, report.result(), report.note());
        return ResponseEntity.noContent().build();
    }
}
