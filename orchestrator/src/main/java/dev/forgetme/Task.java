package dev.forgetme;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/** One connector's share of one request: "orders, please delete alice@example.com". */
@Entity
public class Task {

    public enum Status { PENDING, SENT, DONE, FAILED }

    public enum Result { DELETED, ANONYMIZED, RETAINED }

    @Id
    private UUID id;
    private UUID requestId;
    private UUID connectorId;
    private int stage;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Enumerated(EnumType.STRING)
    private Result result;
    private String note;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant updatedAt;

    protected Task() {}

    Task(UUID requestId, UUID connectorId, int stage) {
        this.id = UUID.randomUUID();
        this.requestId = requestId;
        this.connectorId = connectorId;
        this.stage = stage;
        this.status = Status.PENDING;
        this.nextAttemptAt = Instant.now();
        this.updatedAt = nextAttemptAt;
    }

    boolean isOpen() { return status == Status.PENDING || status == Status.SENT; }

    /** The connector accepted the job; if it hasn't reported back by the deadline, we send again. */
    void sent(Instant callbackDeadline) { status = Status.SENT; nextAttemptAt = callbackDeadline; touch(); }

    void retryAt(Instant when, String error) { status = Status.PENDING; nextAttemptAt = when; note = cap(error); touch(); }

    void fail(String error) { status = Status.FAILED; note = cap(error); touch(); }

    void done(Result result, String note) { status = Status.DONE; this.result = result; this.note = cap(note); touch(); }

    void retryNow() { status = Status.PENDING; attempts = 0; nextAttemptAt = Instant.now(); touch(); }

    private void touch() { updatedAt = Instant.now(); }

    private static String cap(String s) { return s == null || s.length() <= 500 ? s : s.substring(0, 500); }

    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public UUID getConnectorId() { return connectorId; }
    public int getStage() { return stage; }
    public Status getStatus() { return status; }
    public Result getResult() { return result; }
    public String getNote() { return note; }
    public int getAttempts() { return attempts; }
    public Instant getUpdatedAt() { return updatedAt; }
}
