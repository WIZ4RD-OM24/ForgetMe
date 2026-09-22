package dev.forgetme;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Emails the admin when an unfinished request is 7 days from its legal deadline, and again if it goes past it. */
@Component
class DeadlineWatcher {

    static final Duration WARN_BEFORE = Duration.ofDays(7);
    private static final Set<RequestStatus> OPEN = Set.of(RequestStatus.WAITING, RequestStatus.RUNNING, RequestStatus.NEEDS_ATTENTION);
    private static final Logger log = LoggerFactory.getLogger(DeadlineWatcher.class);

    private final PrivacyRequestRepository requests;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final JavaMailSender mail;
    private final String adminEmail;

    DeadlineWatcher(PrivacyRequestRepository requests, TransactionTemplate tx, AuditLog audit, JavaMailSender mail,
                    @Value("${forgetme.admin-email}") String adminEmail) {
        this.requests = requests;
        this.tx = tx;
        this.audit = audit;
        this.mail = mail;
        this.adminEmail = adminEmail;
    }

    @Scheduled(fixedDelayString = "${forgetme.tick}", initialDelayString = "${forgetme.tick}")
    public void check() {
        Instant now = Instant.now();
        for (PrivacyRequest seen : requests.findByStatusInAndDueAtBefore(OPEN, now.plus(WARN_BEFORE))) {
            if (seen.alertDue(now) == null) continue; // the admin already knows
            tx.executeWithoutResult(s -> {
                PrivacyRequest r = requests.findLockedById(seen.getId()).orElseThrow();
                PrivacyRequest.DeadlineAlert level = r.alertDue(now);
                if (level == null || !OPEN.contains(r.getStatus())) return; // changed while we weren't looking
                r.alerted(level);
                audit.record(r.getId(), "DEADLINE_" + level, "due " + r.getDueAt());
                alertAdmin(r, level);
            });
        }
    }

    private void alertAdmin(PrivacyRequest r, PrivacyRequest.DeadlineAlert level) {
        String problem = level == PrivacyRequest.DeadlineAlert.OVERDUE ? "is past its legal deadline" : "is due within 7 days";
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom("no-reply@forgetme.local");
        msg.setTo(adminEmail);
        msg.setSubject("ForgetMe: request " + r.getId() + " " + problem);
        msg.setText("""
                Request %s %s.
                Status: %s
                Due: %s

                See each system's progress: GET /api/requests/%s
                """.formatted(r.getId(), problem, r.getStatus(), r.getDueAt(), r.getId()));
        mail.send(msg);
        log.warn("Request {} {}", r.getId(), problem);
    }
}
