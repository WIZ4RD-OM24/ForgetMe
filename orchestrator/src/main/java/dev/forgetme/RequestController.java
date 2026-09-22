package dev.forgetme;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/requests")
class RequestController {

    record FileBody(@NotBlank @Email String email) {
        FileBody {
            if (email != null) email = email.strip(); // pasted emails often carry spaces; @Email rejects them
        }
    }

    record VerifyBody(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}

    /** What the public sees. Never includes the email. */
    record View(UUID id, RequestStatus status, Instant receivedAt, Instant dueAt) {
        static View of(PrivacyRequest r) {
            return new View(r.getId(), r.getStatus(), r.getReceivedAt(), r.getDueAt());
        }
    }

    /** What the admin sees: the same plus each connector's progress. */
    record Detail(UUID id, RequestStatus status, Instant receivedAt, Instant dueAt, Instant runAfter, List<TaskView> tasks) {}

    record TaskView(String connector, int stage, Task.Status status, Task.Result result, String note, int attempts) {}

    private final RequestService service;
    private final Dispatcher dispatcher;
    private final TaskRepository tasks;
    private final ConnectorRepository connectors;

    RequestController(RequestService service, Dispatcher dispatcher, TaskRepository tasks, ConnectorRepository connectors) {
        this.service = service;
        this.dispatcher = dispatcher;
        this.tasks = tasks;
        this.connectors = connectors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    View file(@Valid @RequestBody FileBody body) {
        return View.of(service.file(body.email()));
    }

    @PostMapping("/{id}/verify")
    ResponseEntity<?> verify(@PathVariable UUID id, @Valid @RequestBody VerifyBody body) {
        PrivacyRequest r = service.verify(id, body.code());
        return switch (r.getStatus()) {
            case WAITING -> ResponseEntity.ok(View.of(r));
            case REJECTED -> problem(HttpStatus.GONE, "Code expired or too many wrong attempts. Please file a new request.");
            default -> problem(HttpStatus.BAD_REQUEST, "Wrong code. %d attempts left."
                    .formatted(RequestService.MAX_CODE_ATTEMPTS - r.getCodeAttempts()));
        };
    }

    @PostMapping("/{id}/cancel")
    View cancel(@PathVariable UUID id) {
        return View.of(service.cancel(id));
    }

    @PostMapping("/{id}/retry")
    View retry(@PathVariable UUID id) {
        return View.of(dispatcher.retry(id));
    }

    @GetMapping("/{id}")
    Detail get(@PathVariable UUID id) {
        PrivacyRequest r = service.get(id);
        Map<UUID, String> names = connectors.findAll().stream().collect(Collectors.toMap(Connector::getId, Connector::getName));
        List<TaskView> progress = tasks.findByRequestId(id).stream()
                .sorted(Comparator.comparing(Task::getStage))
                .map(t -> new TaskView(names.get(t.getConnectorId()), t.getStage(), t.getStatus(), t.getResult(),
                        t.getNote(), t.getAttempts()))
                .toList();
        return new Detail(r.getId(), r.getStatus(), r.getReceivedAt(), r.getDueAt(), r.getRunAfter(), progress);
    }

    @ExceptionHandler(RequestStatus.IllegalTransition.class)
    ProblemDetail conflict(RequestStatus.IllegalTransition e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.of(ProblemDetail.forStatusAndDetail(status, detail)).build();
    }
}
