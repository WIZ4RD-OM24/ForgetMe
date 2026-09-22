package dev.forgetme;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin only: tell ForgetMe which systems hold personal data. */
@RestController
@RequestMapping("/api/connectors")
class ConnectorController {

    record NewConnector(@NotBlank @Size(max = 100) String name,
                        @NotBlank @Size(max = 500) @Pattern(regexp = "https?://\\S+") String endpointUrl,
                        @Min(1) int stage) {}

    record View(UUID id, String name, String endpointUrl, int stage) {}

    /** The secret is shown once, at registration. The connector uses it to check our calls and sign its replies. */
    record Registered(UUID id, String name, String endpointUrl, int stage, String secret) {}

    private final ConnectorRepository connectors;
    private final Crypto crypto;

    ConnectorController(ConnectorRepository connectors, Crypto crypto) {
        this.connectors = connectors;
        this.crypto = crypto;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Registered register(@Valid @RequestBody NewConnector body) {
        String secret = crypto.newSecret();
        Connector c = connectors.save(new Connector(body.name(), body.endpointUrl(), crypto.encrypt(secret), body.stage()));
        return new Registered(c.getId(), c.getName(), c.getEndpointUrl(), c.getStage(), secret);
    }

    @GetMapping
    List<View> list() {
        return connectors.findAll(Sort.by("stage", "name")).stream()
                .map(c -> new View(c.getId(), c.getName(), c.getEndpointUrl(), c.getStage()))
                .toList();
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail duplicateName() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A connector with that name already exists.");
    }
}
