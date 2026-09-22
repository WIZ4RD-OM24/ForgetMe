package dev.forgetme;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests against a real Postgres. Skipped automatically when Docker isn't running.
 * Waits are set to zero and the timer is switched off: tests call dispatcher.tick() themselves, one step at a time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "forgetme.cooling-off=PT0S", "forgetme.retry-backoff=PT0S", "forgetme.callback-timeout=PT0S",
        "forgetme.tick=PT1H", "forgetme.filings-per-hour=1000"}) // the per-IP limit has its own unit test
@Testcontainers(disabledWithoutDocker = true)
class RequestFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    JavaMailSender mail;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Dispatcher dispatcher;

    @Autowired
    DeadlineWatcher deadlineWatcher;

    @Autowired
    Crypto crypto;

    @Autowired
    ObjectMapper json;

    @LocalServerPort
    int port;

    RestClient http;
    final List<FakeConnector> fakes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.sql("truncate audit_event, task, connector, privacy_request").update();
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api")
                .defaultStatusHandler(status -> true, (req, res) -> {}) // assert on 4xx instead of throwing
                .build();
    }

    @AfterEach
    void stopFakes() {
        fakes.forEach(FakeConnector::close);
    }

    // ---- Phase 1: filing and verifying ----

    @Test
    void fileVerifyThenCancel() {
        ResponseEntity<Map> filed = call(POST, "/requests", Map.of("email", "  Alice@Example.com "), false);
        assertEquals(201, filed.getStatusCode().value());
        assertEquals("RECEIVED", filed.getBody().get("status"));
        String id = (String) filed.getBody().get("id");

        SimpleMailMessage email = sentEmail();
        assertArrayEquals(new String[] {"alice@example.com"}, email.getTo());
        String code = codeIn(email);

        byte[] stored = jdbc.sql("select email_enc from privacy_request where id = ?")
                .param(UUID.fromString(id)).query(byte[].class).single();
        assertFalse(new String(stored, UTF_8).contains("alice"), "email must be encrypted at rest");

        assertEquals(401, call(GET, "/requests/" + id, null, false).getStatusCode().value(), "status is admin-only");
        assertEquals(400, verifyCode(id, wrong(code)).getStatusCode().value());

        ResponseEntity<Map> verified = verifyCode(id, code);
        assertEquals(200, verified.getStatusCode().value());
        assertEquals("WAITING", verified.getBody().get("status"));
        assertEquals("WAITING", status(id));
        assertTrue(jdbc.sql("select due_at - received_at = interval '30 days' from privacy_request where id = ?")
                .param(UUID.fromString(id)).query(Boolean.class).single());

        assertEquals("CANCELLED", call(POST, "/requests/" + id + "/cancel", null, false).getBody().get("status"));
        assertEquals(409, call(POST, "/requests/" + id + "/cancel", null, false).getStatusCode().value(), "already cancelled");
        assertTrue(emailErased(id), "a finished request keeps no email, even a cancelled one");
    }

    @Test
    void fiveWrongCodesRejectTheRequest() {
        String id = (String) call(POST, "/requests", Map.of("email", "bob@example.com"), false).getBody().get("id");
        String code = codeIn(sentEmail());

        for (int i = 1; i < RequestService.MAX_CODE_ATTEMPTS; i++) {
            assertEquals(400, verifyCode(id, wrong(code)).getStatusCode().value());
        }
        assertEquals(410, verifyCode(id, wrong(code)).getStatusCode().value());
        assertEquals(409, verifyCode(id, code).getStatusCode().value(), "even the right code is too late now");
    }

    @Test
    void unverifiedRequestsExpire() {
        String id = (String) call(POST, "/requests", Map.of("email", "gina@example.com"), false).getBody().get("id");
        jdbc.sql("update privacy_request set code_expires_at = now() - interval '1 minute' where id = ?")
                .param(UUID.fromString(id)).update(); // pretend 24 hours went by
        dispatcher.tick();
        assertEquals("REJECTED", status(id));
        assertTrue(emailErased(id));
    }

    // ---- Phase 2: fan-out to connectors ----

    @Test
    void deletesStageByStageAndSurvivesAFlakyConnector() {
        FakeConnector mailing = fake(500, 500); // fails twice, then accepts
        FakeConnector orders = fake();
        FakeConnector uploads = fake();
        String mailingSecret = register("mailing", mailing, 1);
        String ordersSecret = register("orders", orders, 2);
        String uploadsSecret = register("uploads", uploads, 2);
        String id = fileAndVerify("carol@example.com");

        dispatcher.tick(); // cooling-off over → RUNNING; stage 1 sent → 500
        dispatcher.tick(); // 500 again
        dispatcher.tick(); // accepted
        assertEquals(3, mailing.received.size());
        assertTrue(orders.received.isEmpty(), "stage 2 waits for stage 1");

        FakeConnector.Received call = mailing.last();
        assertTrue(Crypto.verify(mailingSecret, call.timestamp(), call.body(), call.signature()), "our calls are signed");
        Map<?, ?> payload = json.readValue(call.body(), Map.class);
        assertEquals("carol@example.com", payload.get("email"));
        assertTrue(((String) payload.get("callbackUrl")).endsWith("/api/callbacks/" + payload.get("taskId")));

        assertEquals(204, report(mailingSecret, mailing.last(), "DELETED", null));
        dispatcher.tick(); // stage 2: both at once
        assertEquals(1, orders.received.size());
        assertEquals(1, uploads.received.size());
        assertEquals(204, report(ordersSecret, orders.last(), "RETAINED", "invoices kept 8 years: tax law"));
        assertEquals(204, report(uploadsSecret, uploads.last(), "DELETED", null));

        Map<?, ?> detail = call(GET, "/requests/" + id, null, true).getBody();
        assertEquals("COMPLETED", detail.get("status"));
        List<Map<?, ?>> tasks = (List<Map<?, ?>>) detail.get("tasks");
        assertEquals(3, tasks.size());
        assertTrue(tasks.stream().allMatch(t -> "DONE".equals(t.get("status"))));
        Map<?, ?> ordersTask = tasks.stream().filter(t -> "orders".equals(t.get("connector"))).findFirst().orElseThrow();
        assertEquals("RETAINED", ordersTask.get("result"));
        assertEquals("invoices kept 8 years: tax law", ordersTask.get("note"));

        assertEquals(204, report(uploadsSecret, uploads.last(), "DELETED", null), "a repeated report is harmless");
        assertEquals("COMPLETED", status(id));
    }

    @Test
    void outOfAttemptsNeedsAttentionUntilAnAdminRetries() {
        FakeConnector search = fake(500, 500, 500, 500, 500);
        String secret = register("search", search, 1);
        String id = fileAndVerify("erin@example.com");

        for (int i = 0; i < Dispatcher.MAX_ATTEMPTS; i++) dispatcher.tick();
        assertEquals(Dispatcher.MAX_ATTEMPTS, search.received.size());
        assertEquals("NEEDS_ATTENTION", status(id));
        dispatcher.tick();
        assertEquals(Dispatcher.MAX_ATTEMPTS, search.received.size(), "a failed task waits for a human");

        assertEquals(401, call(POST, "/requests/" + id + "/retry", null, false).getStatusCode().value(), "admin only");
        assertEquals("RUNNING", call(POST, "/requests/" + id + "/retry", null, true).getBody().get("status"));
        dispatcher.tick(); // accepted this time
        assertEquals(204, report(secret, search.last(), "DELETED", null));
        assertEquals("COMPLETED", status(id));
    }

    @Test
    void sendsAgainWhenAConnectorNeverReportsBack() {
        FakeConnector analytics = fake();
        String secret = register("analytics", analytics, 1);
        String id = fileAndVerify("frank@example.com");

        dispatcher.tick(); // accepted, but no report comes
        dispatcher.tick(); // report deadline (zero in tests) has passed → send again
        assertEquals(2, analytics.received.size());
        assertEquals(taskIdOf(analytics.received.get(0)), taskIdOf(analytics.received.get(1)),
                "same task ID both times, so the connector can spot the repeat");
        assertEquals(204, report(secret, analytics.last(), "DELETED", null));
        assertEquals("COMPLETED", status(id));
    }

    @Test
    void rejectsForgedStaleAndBrokenReports() {
        FakeConnector orders = fake();
        String secret = register("orders", orders, 1);
        assertEquals(401, call(POST, "/connectors", Map.of("name", "x", "endpointUrl", "http://x", "stage", 1), false)
                .getStatusCode().value(), "registering connectors is admin only");
        fileAndVerify("dave@example.com");
        dispatcher.tick();
        String taskId = taskIdOf(orders.last());
        String body = "{\"result\":\"DELETED\"}";
        long now = Instant.now().getEpochSecond();

        assertEquals(401, rawReport(taskId, now, body, Crypto.sign("wrong-secret", now, body)), "forged");
        assertEquals(401, rawReport(taskId, now - 600, body, Crypto.sign(secret, now - 600, body)), "replayed later");
        assertEquals(404, rawReport(UUID.randomUUID().toString(), now, body, Crypto.sign(secret, now, body)));
        String nonsense = "{\"result\":\"SHREDDED\"}";
        assertEquals(400, rawReport(taskId, now, nonsense, Crypto.sign(secret, now, nonsense)));
    }

    @Test
    void aConnectorCanBringItsOwnSecret() {
        String own = "demo-only-secret-users-change-me";
        Map<String, Object> body = Map.of("name", "users", "endpointUrl", "http://users:8080/privacy/erase", "stage", 3, "secret", own);
        assertEquals(own, call(POST, "/connectors", body, true).getBody().get("secret"));

        Map<String, Object> weak = Map.of("name", "weak", "endpointUrl", "http://weak/erase", "stage", 1, "secret", "too-short");
        assertEquals(400, call(POST, "/connectors", weak, true).getStatusCode().value(), "short secrets are refused");
    }

    @Test
    void oneAddressCantBeFloodedWithRequests() {
        for (int i = 0; i < RequestService.MAX_PER_EMAIL_PER_DAY; i++) {
            assertEquals(201, call(POST, "/requests", Map.of("email", "target@example.com"), false).getStatusCode().value());
        }
        assertEquals(429, call(POST, "/requests", Map.of("email", "TARGET@example.com"), false).getStatusCode().value(),
                "same address, whatever the capitals");
        assertEquals(201, call(POST, "/requests", Map.of("email", "someone-else@example.com"), false).getStatusCode().value());
    }

    @Test
    void anyoneCanAskConfirmAndWatchFromTheWebPages() {
        assertTrue(page("/", false).getBody().contains("Delete my data"));

        String location = redirectOf(form("/", "email=olive@example.com"));
        assertTrue(location.startsWith("/r/"), "sent to its own status page");
        String id = location.substring(3);
        assertTrue(page(location, false).getBody().contains("Enter your code"), "asks for the code");

        assertEquals("/r/" + id + "?wrong", redirectOf(form(location + "/confirm", "code=000001")));
        assertTrue(page(location + "?wrong", false).getBody().contains("isn't right"));

        form(location + "/confirm", "code=" + codeIn(sentEmail()));
        assertTrue(page(location, false).getBody().contains("Confirmed"), "now waiting for the cooling-off");

        form(location + "/cancel", null);
        assertTrue(page(location, false).getBody().contains("Cancelled"));
        assertEquals("CANCELLED", status(id));
    }

    @Test
    void adminPageListsRequestsAndIsPrivate() {
        String id = fileAndVerify("nina@example.com");
        assertEquals(401, page("/admin", false).getStatusCode().value());

        ResponseEntity<String> list = page("/admin", true);
        assertEquals(200, list.getStatusCode().value());
        assertTrue(list.getBody().contains(id.substring(0, 8)), "the request is listed");
        assertTrue(list.getBody().contains("Audit log intact"));
        assertTrue(page("/admin/requests/" + id, true).getBody().contains("WAITING"));
    }

    // ---- Phase 3: proof and deadlines ----

    @Test
    void certificateProvesWhatEachSystemDid() {
        FakeConnector mailing = fake();
        FakeConnector orders = fake();
        String mailingSecret = register("mailing", mailing, 1);
        String ordersSecret = register("orders", orders, 2);
        String id = fileAndVerify("hana@example.com");

        dispatcher.tick();
        assertEquals(409, call(GET, "/requests/" + id + "/certificate", null, true).getStatusCode().value(),
                "no certificate while it's still running");
        report(mailingSecret, mailing.last(), "DELETED", null);
        dispatcher.tick();
        report(ordersSecret, orders.last(), "RETAINED", "invoices kept 8 years: tax law");
        assertEquals("COMPLETED", status(id));

        assertTrue(emailErased(id), "ForgetMe forgets too");
        ResponseEntity<Map> response = call(GET, "/requests/" + id + "/certificate", null, true);
        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> certificate = response.getBody();
        assertEquals(true, certificate.get("onTime"));
        assertEquals(HexFormat.of().formatHex(crypto.subjectHash("hana@example.com")), certificate.get("subjectFingerprint"));
        assertFalse(json.writeValueAsString(certificate).contains("hana"), "no email anywhere on the certificate");

        List<Map<?, ?>> systems = (List<Map<?, ?>>) certificate.get("systems");
        assertEquals(List.of("mailing", "orders"), systems.stream().map(s -> s.get("system")).toList());
        assertEquals("RETAINED", systems.get(1).get("result"));
        assertEquals("invoices kept 8 years: tax law", systems.get(1).get("note"));

        List<Map<?, ?>> history = (List<Map<?, ?>>) certificate.get("history");
        assertEquals(List.of("RECEIVED", "WAITING", "RUNNING", "STAGE_STARTED", "TASK_DONE", "STAGE_STARTED", "TASK_DONE", "COMPLETED"),
                history.stream().map(h -> h.get("event")).toList());
        String lastHash = jdbc.sql("select encode(hash, 'hex') from audit_event order by id desc limit 1").query(String.class).single();
        assertEquals(lastHash, certificate.get("auditHash"), "the certificate pins the audit log's latest link");
        assertEquals(true, call(GET, "/audit/verify", null, true).getBody().get("intact"));
    }

    @Test
    void auditLogCatchesAnEditedEvent() {
        String id = fileAndVerify("ivan@example.com");
        call(POST, "/requests/" + id + "/cancel", null, false);
        assertEquals(Map.of("intact", true, "eventsChecked", 3), withoutNulls(call(GET, "/audit/verify", null, true).getBody()));

        List<Long> ids = jdbc.sql("select id from audit_event order by id").query(Long.class).list();
        assertThrows(Exception.class, () -> jdbc.sql("update audit_event set detail = 'nothing to see' where id = ?")
                .param(ids.get(1)).update(), "the database refuses edits");
        tamper("update audit_event set detail = 'nothing to see' where id = " + ids.get(1));

        Map<?, ?> check = call(GET, "/audit/verify", null, true).getBody();
        assertEquals(false, check.get("intact"));
        assertEquals(ids.get(1).intValue(), ((Number) check.get("firstBrokenEventId")).intValue());
    }

    @Test
    void auditLogCatchesADeletedEvent() {
        String id = fileAndVerify("jade@example.com");
        call(POST, "/requests/" + id + "/cancel", null, false);
        List<Long> ids = jdbc.sql("select id from audit_event order by id").query(Long.class).list();

        tamper("delete from audit_event where id = " + ids.get(1));

        Map<?, ?> check = call(GET, "/audit/verify", null, true).getBody();
        assertEquals(false, check.get("intact"));
        assertEquals(ids.get(2).intValue(), ((Number) check.get("firstBrokenEventId")).intValue(),
                "the event after the gap no longer links up");
    }

    @Test
    void deadlineWarningsReachTheAdminOnceEach() {
        String id = fileAndVerify("kim@example.com");
        String cancelled = fileAndVerify("lee@example.com");
        call(POST, "/requests/" + cancelled + "/cancel", null, false);
        jdbc.sql("update privacy_request set due_at = now() + interval '3 days'").update(); // pretend weeks went by

        deadlineWatcher.check();
        deadlineWatcher.check();
        List<SimpleMailMessage> alerts = adminEmails();
        assertEquals(1, alerts.size(), "one warning, not one per check, and none for the cancelled request");
        assertTrue(alerts.getFirst().getSubject().contains(id));
        assertTrue(alerts.getFirst().getSubject().contains("due within 7 days"));

        jdbc.sql("update privacy_request set due_at = now() - interval '1 hour'").update();
        deadlineWatcher.check();
        deadlineWatcher.check();
        alerts = adminEmails();
        assertEquals(2, alerts.size());
        assertTrue(alerts.getLast().getSubject().contains("past its legal deadline"));
        assertEquals(List.of("DEADLINE_WARNING", "DEADLINE_OVERDUE"),
                jdbc.sql("select event from audit_event where event like 'DEADLINE%' order by id").query(String.class).list());
    }

    // ---- helpers ----

    /** Plays an attacker with full database rights: switches off the append-only guard, edits, switches it back on. */
    private void tamper(String sql) {
        jdbc.sql("alter table audit_event disable trigger audit_event_append_only").update();
        try {
            jdbc.sql(sql).update();
        } finally {
            jdbc.sql("alter table audit_event enable trigger audit_event_append_only").update();
        }
    }

    private boolean emailErased(String id) {
        return jdbc.sql("select email_enc is null from privacy_request where id = ?")
                .param(UUID.fromString(id)).query(Boolean.class).single();
    }

    private List<SimpleMailMessage> adminEmails() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().filter(m -> List.of(m.getTo()).contains("admin@forgetme.local")).toList();
    }

    private static Map<?, ?> withoutNulls(Map<?, ?> map) {
        return map.entrySet().stream().filter(e -> e.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private ResponseEntity<Map> call(HttpMethod method, String path, Object body, boolean asAdmin) {
        RestClient.RequestBodySpec spec = http.method(method).uri(path);
        if (asAdmin) spec.headers(h -> h.setBasicAuth("admin", "admin"));
        if (body != null) spec.contentType(MediaType.APPLICATION_JSON).body(body);
        return spec.retrieve().toEntity(Map.class);
    }

    /** The admin pages live outside /api, so these go to the server root. */
    private ResponseEntity<String> page(String path, boolean asAdmin) {
        return http.get().uri("http://localhost:" + port + path)
                .headers(h -> { if (asAdmin) h.setBasicAuth("admin", "admin"); })
                .retrieve().toEntity(String.class);
    }

    /** Where a redirect points, as a path: Tomcat sends the Location header as a full URL. */
    private String redirectOf(ResponseEntity<Void> response) {
        return URI.create(response.getHeaders().getFirst("Location")).getPath()
                + (URI.create(response.getHeaders().getFirst("Location")).getQuery() == null ? ""
                : "?" + URI.create(response.getHeaders().getFirst("Location")).getQuery());
    }

    /** Submits one of the public pages' forms, like a browser would. */
    private ResponseEntity<Void> form(String path, String formBody) {
        RestClient.RequestBodySpec spec = http.post().uri("http://localhost:" + port + path);
        if (formBody != null) spec.contentType(MediaType.APPLICATION_FORM_URLENCODED).body(formBody);
        return spec.retrieve().toBodilessEntity();
    }

    private ResponseEntity<Map> verifyCode(String id, String code) {
        return call(POST, "/requests/" + id + "/verify", Map.of("code", code), false);
    }

    private String status(String id) {
        return (String) call(GET, "/requests/" + id, null, true).getBody().get("status");
    }

    private String fileAndVerify(String email) {
        String id = (String) call(POST, "/requests", Map.of("email", email), false).getBody().get("id");
        assertEquals(200, verifyCode(id, codeIn(sentEmail())).getStatusCode().value());
        return id;
    }

    private String register(String name, FakeConnector fake, int stage) {
        ResponseEntity<Map> r = call(POST, "/connectors", Map.of("name", name, "endpointUrl", fake.url(), "stage", stage), true);
        assertEquals(201, r.getStatusCode().value());
        return (String) r.getBody().get("secret");
    }

    /** Plays the connector: reports back on the task it was sent, signed with its secret. */
    private int report(String secret, FakeConnector.Received to, String result, String note) {
        Map<String, String> report = note == null ? Map.of("result", result) : Map.of("result", result, "note", note);
        String body = json.writeValueAsString(report);
        long now = Instant.now().getEpochSecond();
        return rawReport(taskIdOf(to), now, body, Crypto.sign(secret, now, body));
    }

    private int rawReport(String taskId, long timestamp, String body, String signature) {
        return http.post().uri("/callbacks/" + taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(Crypto.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .header(Crypto.SIGNATURE_HEADER, signature)
                .body(body)
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private String taskIdOf(FakeConnector.Received call) {
        return (String) json.readValue(call.body(), Map.class).get("taskId");
    }

    /** The most recent email ForgetMe sent. */
    private SimpleMailMessage sentEmail() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(captor.capture());
        return captor.getValue();
    }

    private static String codeIn(SimpleMailMessage email) {
        Matcher m = Pattern.compile("code is (\\d{6})").matcher(email.getText());
        assertTrue(m.find(), "email should contain a 6-digit code");
        return m.group(1);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private FakeConnector fake(Integer... replies) {
        FakeConnector fake = new FakeConnector(replies);
        fakes.add(fake);
        return fake;
    }

    /** A pretend connector on a random local port, built on the JDK's own tiny web server. */
    static class FakeConnector implements AutoCloseable {

        record Received(String body, long timestamp, String signature) {}

        final List<Received> received = new CopyOnWriteArrayList<>();
        private final Deque<Integer> replies; // status codes to answer with, in order; 202 once they run out
        private final HttpServer server;

        FakeConnector(Integer... replies) {
            this.replies = new ConcurrentLinkedDeque<>(List.of(replies));
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", exchange -> {
                received.add(new Received(new String(exchange.getRequestBody().readAllBytes(), UTF_8),
                        Long.parseLong(exchange.getRequestHeaders().getFirst(Crypto.TIMESTAMP_HEADER)),
                        exchange.getRequestHeaders().getFirst(Crypto.SIGNATURE_HEADER)));
                Integer status = this.replies.poll();
                exchange.sendResponseHeaders(status == null ? 202 : status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort() + "/privacy/erase";
        }

        Received last() {
            return received.getLast();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
