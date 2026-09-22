package dev.forgetme;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RequestService {

    public static final int MAX_CODE_ATTEMPTS = 5;
    static final Duration CODE_TTL = Duration.ofHours(24);
    static final Duration DEADLINE = Duration.ofDays(30); // GDPR: one month

    private final PrivacyRequestRepository repo;
    private final Crypto crypto;
    private final JavaMailSender mail;
    private final Duration coolingOff;

    public RequestService(PrivacyRequestRepository repo, Crypto crypto, JavaMailSender mail,
                          @Value("${forgetme.cooling-off}") Duration coolingOff) {
        this.repo = repo;
        this.crypto = crypto;
        this.mail = mail;
        this.coolingOff = coolingOff;
    }

    @Transactional
    public PrivacyRequest file(String email) {
        String normalized = email.toLowerCase(Locale.ROOT);
        UUID id = UUID.randomUUID();
        String code = crypto.newCode();
        Instant now = Instant.now();
        PrivacyRequest request = repo.save(new PrivacyRequest(id, crypto.encrypt(normalized),
                crypto.codeHash(id, code), now, now.plus(CODE_TTL), now.plus(DEADLINE)));

        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom("no-reply@forgetme.local");
        msg.setTo(normalized);
        msg.setSubject("Confirm your data deletion request");
        msg.setText("""
                Someone asked us to delete all data linked to this email address.

                Your confirmation code is %s. It expires in %d hours.
                Request ID: %s

                If this wasn't you, ignore this email and nothing will happen.
                """.formatted(code, CODE_TTL.toHours(), id));
        // ponytail: sent inside the transaction; if the commit fails after sending, the user gets a dead code. Outbox if that matters.
        mail.send(msg);
        return request;
    }

    /**
     * Returns the request in its new state: WAITING (right code), RECEIVED (wrong code, attempts left)
     * or REJECTED (expired or out of attempts). Deliberately returns instead of throwing for a wrong code:
     * an exception would roll back the transaction and with it the attempt counter.
     */
    @Transactional
    public PrivacyRequest verify(UUID id, String code) {
        PrivacyRequest request = findLocked(id);
        if (request.getStatus() != RequestStatus.RECEIVED) {
            throw new RequestStatus.IllegalTransition(request.getStatus(), RequestStatus.WAITING);
        }
        if (Instant.now().isAfter(request.getCodeExpiresAt())) {
            request.moveTo(RequestStatus.REJECTED);
        } else if (crypto.codeMatches(request.getCodeHash(), id, code)) {
            request.verified(Instant.now().plus(coolingOff));
        } else {
            request.wrongCode();
            if (request.getCodeAttempts() >= MAX_CODE_ATTEMPTS) request.moveTo(RequestStatus.REJECTED);
        }
        return request;
    }

    @Transactional
    public PrivacyRequest cancel(UUID id) {
        PrivacyRequest request = findLocked(id);
        request.moveTo(RequestStatus.CANCELLED);
        return request;
    }

    @Transactional(readOnly = true)
    public PrivacyRequest get(UUID id) {
        return repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private PrivacyRequest findLocked(UUID id) {
        return repo.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
