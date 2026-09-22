package dev.forgetme;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class PrivacyRequest {

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private RequestStatus status;
    private byte[] emailEnc;
    private byte[] codeHash;
    private Instant codeExpiresAt;
    private int codeAttempts;
    private Instant receivedAt;
    private Instant dueAt;
    private Instant runAfter;

    protected PrivacyRequest() {}

    PrivacyRequest(UUID id, byte[] emailEnc, byte[] codeHash, Instant receivedAt, Instant codeExpiresAt, Instant dueAt) {
        this.id = id;
        this.status = RequestStatus.RECEIVED;
        this.emailEnc = emailEnc;
        this.codeHash = codeHash;
        this.receivedAt = receivedAt;
        this.codeExpiresAt = codeExpiresAt;
        this.dueAt = dueAt;
    }

    void moveTo(RequestStatus to) {
        status = status.moveTo(to);
        if (to != RequestStatus.RECEIVED) codeHash = null; // code is single-use
    }

    void wrongCode() { codeAttempts++; }

    /** Right code: deletion starts once the cooling-off period is over. */
    void verified(Instant runAfter) {
        moveTo(RequestStatus.WAITING);
        this.runAfter = runAfter;
    }

    public UUID getId() { return id; }
    public RequestStatus getStatus() { return status; }
    public byte[] getEmailEnc() { return emailEnc; }
    public Instant getRunAfter() { return runAfter; }
    public byte[] getCodeHash() { return codeHash; }
    public Instant getCodeExpiresAt() { return codeExpiresAt; }
    public int getCodeAttempts() { return codeAttempts; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getDueAt() { return dueAt; }
}
