package dev.forgetme;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class PrivacyRequest {

    public enum DeadlineAlert { WARNING, OVERDUE }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    private RequestStatus status;
    private byte[] emailEnc;
    private byte[] subjectHash;
    private byte[] codeHash;
    private Instant codeExpiresAt;
    private int codeAttempts;
    private Instant receivedAt;
    private Instant dueAt;
    private Instant runAfter;
    private Instant closedAt;
    @Enumerated(EnumType.STRING)
    private DeadlineAlert deadlineAlert;

    protected PrivacyRequest() {}

    PrivacyRequest(UUID id, byte[] emailEnc, byte[] subjectHash, byte[] codeHash,
                   Instant receivedAt, Instant codeExpiresAt, Instant dueAt) {
        this.id = id;
        this.status = RequestStatus.RECEIVED;
        this.emailEnc = emailEnc;
        this.subjectHash = subjectHash;
        this.codeHash = codeHash;
        this.receivedAt = receivedAt;
        this.codeExpiresAt = codeExpiresAt;
        this.dueAt = dueAt;
    }

    /** Use AuditLog.move() instead, so every change of status is written to the audit log. */
    void moveTo(RequestStatus to) {
        status = status.moveTo(to);
        codeHash = null; // the code is single-use, and every move leaves RECEIVED behind
        if (to.next().isEmpty()) { // finished for good: keep no readable personal data
            emailEnc = null;
            closedAt = Instant.now();
        }
    }

    void wrongCode() { codeAttempts++; }

    void coolOffUntil(Instant runAfter) { this.runAfter = runAfter; }

    /** The deadline alert this request should get now, or null if the admin already had it. */
    DeadlineAlert alertDue(Instant now) {
        DeadlineAlert level = dueAt.isBefore(now) ? DeadlineAlert.OVERDUE : DeadlineAlert.WARNING;
        return deadlineAlert != null && deadlineAlert.compareTo(level) >= 0 ? null : level;
    }

    void alerted(DeadlineAlert level) { deadlineAlert = level; }

    public UUID getId() { return id; }
    public RequestStatus getStatus() { return status; }
    public byte[] getEmailEnc() { return emailEnc; }
    public byte[] getSubjectHash() { return subjectHash; }
    public byte[] getCodeHash() { return codeHash; }
    public Instant getCodeExpiresAt() { return codeExpiresAt; }
    public int getCodeAttempts() { return codeAttempts; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getDueAt() { return dueAt; }
    public Instant getRunAfter() { return runAfter; }
    public Instant getClosedAt() { return closedAt; }
}
