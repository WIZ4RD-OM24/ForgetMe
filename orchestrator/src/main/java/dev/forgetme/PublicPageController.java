package dev.forgetme;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the person asking to be deleted sees: ask, confirm with the emailed code, then watch it happen.
 * No login: knowing the request's random ID is what proves it's yours.
 */
@Controller
class PublicPageController {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm 'on' d MMM").withZone(ZoneId.systemDefault());

    private final RequestService service;
    private final RateLimiter limiter;
    private final AuditLog audit;
    private final String inboxUrl;

    PublicPageController(RequestService service, RateLimiter limiter, AuditLog audit,
                         @Value("${forgetme.demo-inbox-url:}") String inboxUrl) {
        this.service = service;
        this.limiter = limiter;
        this.audit = audit;
        this.inboxUrl = inboxUrl;
    }

    @GetMapping("/")
    String home(Model model) {
        model.addAttribute("inboxUrl", inboxUrl);
        return "public/home";
    }

    @PostMapping("/")
    String file(@RequestParam String email, HttpServletRequest caller, Model model) {
        try {
            if (!email.strip().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That doesn't look like an email address.");
            }
            if (!limiter.allow(caller.getRemoteAddr(), Instant.now())) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests from here. Try again later.");
            }
            return "redirect:/r/" + service.file(email.strip()).getId();
        } catch (ResponseStatusException e) {
            model.addAttribute("error", e.getReason());
            model.addAttribute("email", email);
            model.addAttribute("inboxUrl", inboxUrl);
            return "public/home";
        }
    }

    /** One page for the whole journey: it shows the code box, then the progress, then the receipt. */
    @GetMapping("/r/{id}")
    String status(@PathVariable UUID id, @RequestParam(required = false) String wrong, Model model) {
        PrivacyRequest request = service.get(id);
        model.addAttribute("request", request);
        model.addAttribute("tasks", service.progress(id));
        model.addAttribute("wrongCode", wrong != null);
        model.addAttribute("inboxUrl", inboxUrl);
        model.addAttribute("working", List.of(RequestStatus.WAITING, RequestStatus.RUNNING, RequestStatus.NEEDS_ATTENTION)
                .contains(request.getStatus()));
        if (request.getRunAfter() != null) model.addAttribute("startsAt", WHEN.format(request.getRunAfter()));
        if (request.getStatus() == RequestStatus.COMPLETED) {
            List<AuditLog.Event> history = audit.history(id);
            model.addAttribute("completedAt", WHEN.format(request.getClosedAt()));
            model.addAttribute("onTime", !request.getClosedAt().isAfter(request.getDueAt()));
            model.addAttribute("fingerprint", shortHex(request.getSubjectHash()));
            model.addAttribute("auditHash", history.isEmpty() ? null : shortHex(history.getLast().hash()));
        }
        return "public/status";
    }

    @PostMapping("/r/{id}/confirm")
    String confirm(@PathVariable UUID id, @RequestParam String code) {
        try {
            PrivacyRequest request = service.verify(id, code.strip());
            return "redirect:/r/" + id + (request.getStatus() == RequestStatus.RECEIVED ? "?wrong" : "");
        } catch (RequestStatus.IllegalTransition alreadyMovedOn) {
            return "redirect:/r/" + id;
        }
    }

    @PostMapping("/r/{id}/cancel")
    String cancel(@PathVariable UUID id) {
        try {
            service.cancel(id);
        } catch (RequestStatus.IllegalTransition tooLate) {
            // deletion already started or finished; the page will say so
        }
        return "redirect:/r/" + id;
    }

    private static String shortHex(byte[] bytes) {
        return bytes == null ? null : HexFormat.of().formatHex(bytes).substring(0, 16) + "…";
    }
}
