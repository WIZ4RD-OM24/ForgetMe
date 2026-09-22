package dev.forgetme.connector;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

/** A tiny app using the starter, and a pretend ForgetMe on the other end that receives the reports. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ErasureEndpointTest.ConnectorApp.class,
        properties = "forgetme.connector.secret=" + ErasureEndpointTest.SECRET)
class ErasureEndpointTest {

    static final String SECRET = "test-secret-at-least-32-characters-long";
    static final List<String> erased = new CopyOnWriteArrayList<>();

    /** Deletes whoever it's asked to, except "broken@example.com", which fails like a database outage would. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class ConnectorApp {
        @Bean
        ErasureHandler handler() {
            return subject -> {
                if (subject.email().equals("broken@example.com")) throw new IllegalStateException("database down");
                erased.add(subject.email());
                return ErasureResult.retained("invoices kept 8 years: tax law");
            };
        }
    }

    record Report(String body, long timestamp, String signature) {}

    @LocalServerPort
    int port;

    final HttpClient client = HttpClient.newHttpClient();
    final BlockingQueue<Report> reports = new LinkedBlockingQueue<>();
    HttpServer forgetMe;

    @BeforeEach
    void startPretendForgetMe() throws IOException {
        erased.clear();
        forgetMe = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        forgetMe.createContext("/", exchange -> {
            reports.add(new Report(new String(exchange.getRequestBody().readAllBytes(), UTF_8),
                    Long.parseLong(exchange.getRequestHeaders().getFirst(Signatures.TIMESTAMP_HEADER)),
                    exchange.getRequestHeaders().getFirst(Signatures.SIGNATURE_HEADER)));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        forgetMe.start();
    }

    @AfterEach
    void stop() {
        forgetMe.stop(0);
    }

    @Test
    void runsTheHandlerAndReportsBackSigned() throws Exception {
        assertEquals(202, sendJob("alice@example.com", SECRET));
        assertEquals(List.of("alice@example.com"), erased);

        Report report = reports.poll(5, TimeUnit.SECONDS);
        assertTrue(report != null, "a report should arrive");
        assertEquals("{\"result\":\"RETAINED\",\"note\":\"invoices kept 8 years: tax law\"}", report.body());
        assertTrue(Signatures.verify(SECRET, report.timestamp(), report.body(), report.signature()), "reports are signed");
    }

    @Test
    void refusesJobsWithoutTheRightStamp() throws Exception {
        assertEquals(401, sendJob("alice@example.com", "somebody-elses-secret-that-is-also-long"));
        assertTrue(erased.isEmpty(), "nothing deleted");
        assertNull(reports.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    void aFailingHandlerAsksForARetry() throws Exception {
        assertEquals(500, sendJob("broken@example.com", SECRET));
        assertNull(reports.poll(300, TimeUnit.MILLISECONDS), "no report, so ForgetMe tries again");
    }

    /** Plays ForgetMe sending a job, signed with the given secret. */
    private int sendJob(String email, String signWith) throws Exception {
        String body = """
                {"taskId":"%s","requestId":"%s","email":"%s","callbackUrl":"http://localhost:%d/api/callbacks/x"}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), email, forgetMe.getAddress().getPort());
        long now = Instant.now().getEpochSecond();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/privacy/erase"))
                .header("Content-Type", "application/json")
                .header(Signatures.TIMESTAMP_HEADER, String.valueOf(now))
                .header(Signatures.SIGNATURE_HEADER, Signatures.sign(signWith, now, body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
