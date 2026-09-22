package dev.forgetme;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** The small web page an admin actually looks at. Everything here is read-only except the retry button. */
@Controller
@RequestMapping("/admin")
class AdminPageController {

    /** Dates are formatted here so the templates stay plain. */
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    record Row(UUID id, String shortId, RequestStatus status, String received, String deadline, boolean overdue) {}

    record HistoryRow(String when, String event, String detail) {}

    private final PrivacyRequestRepository requests;
    private final ConnectorRepository connectors;
    private final RequestService service;
    private final Dispatcher dispatcher;
    private final AuditLog audit;

    AdminPageController(PrivacyRequestRepository requests, ConnectorRepository connectors, RequestService service,
                        Dispatcher dispatcher, AuditLog audit) {
        this.requests = requests;
        this.connectors = connectors;
        this.service = service;
        this.dispatcher = dispatcher;
        this.audit = audit;
    }

    @GetMapping
    String list(Model model) {
        Instant now = Instant.now();
        List<Row> rows = requests.findTop50ByOrderByReceivedAtDesc().stream()
                .map(r -> new Row(r.getId(), r.getId().toString().substring(0, 8), r.getStatus(), WHEN.format(r.getReceivedAt()),
                        r.getDueAt().isBefore(now) ? "overdue" : Duration.between(now, r.getDueAt()).toDays() + " days left",
                        r.getDueAt().isBefore(now)))
                .toList();
        model.addAttribute("rows", rows);
        model.addAttribute("connectors", connectors.findAll());
        model.addAttribute("audit", audit.verify());
        return "admin/requests";
    }

    @GetMapping("/requests/{id}")
    String detail(@PathVariable UUID id, Model model) {
        PrivacyRequest request = service.get(id);
        model.addAttribute("request", request);
        model.addAttribute("received", WHEN.format(request.getReceivedAt()));
        model.addAttribute("deadline", WHEN.format(request.getDueAt()));
        model.addAttribute("tasks", service.progress(id));
        model.addAttribute("history", audit.history(id).stream()
                .map(e -> new HistoryRow(WHEN.format(e.at()), e.event(), e.detail()))
                .toList());
        return "admin/request";
    }

    @PostMapping("/requests/{id}/retry")
    String retry(@PathVariable UUID id) {
        dispatcher.retry(id);
        return "redirect:/admin/requests/" + id;
    }
}
