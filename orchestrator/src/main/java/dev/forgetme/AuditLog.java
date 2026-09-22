package dev.forgetme;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The append-only history of every request. Each event stores a keyed hash of itself plus the previous event's hash,
 * so editing, deleting or reordering any event breaks every link after it.
 * Never put personal data in here: it's kept forever, long after a request's own identifiers are erased.
 */
@Component
public class AuditLog {

    public record Event(long id, UUID requestId, String event, String detail, Instant at, byte[] prevHash, byte[] hash) {}

    public record Check(boolean intact, long eventsChecked, Long firstBrokenEventId) {}

    private static final byte[] GENESIS = new byte[32]; // what the very first event links back to
    private static final long LOCK_KEY = 0x466f726765744d65L; // "ForgetMe" in ASCII: any fixed number works

    private final JdbcClient jdbc;
    private final Crypto crypto;

    public AuditLog(JdbcClient jdbc, Crypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    /** Changes a request's status and writes it down, in one go, so no status change goes unrecorded. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void move(PrivacyRequest request, RequestStatus to, String detail) {
        request.moveTo(to);
        record(request.getId(), to.name(), detail);
    }

    /** Must run inside the caller's transaction: that's what keeps writers in single file until they commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID requestId, String event, String detail) {
        if (detail != null && detail.length() > 500) detail = detail.substring(0, 500); // column limit
        // One writer at a time, or two events could both link to the same predecessor and fork the chain.
        // ponytail: every audit write in the system queues on this one lock; fine for thousands of events a minute.
        jdbc.sql("select pg_advisory_xact_lock(?)").param(LOCK_KEY).query((rs, n) -> 1).single();
        byte[] prev = jdbc.sql("select hash from audit_event order by id desc limit 1")
                .query(byte[].class).optional().orElse(GENESIS);
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres keeps microseconds: hash exactly what's stored
        jdbc.sql("insert into audit_event (request_id, event, detail, created_at, prev_hash, hash) values (?, ?, ?, ?, ?, ?)")
                .params(requestId, event, detail, Timestamp.from(at), prev, crypto.chainHash(prev, requestId, event, detail, at))
                .update();
    }

    public List<Event> history(UUID requestId) {
        return jdbc.sql("select * from audit_event where request_id = ? order by id").param(requestId).query(this::row).list();
    }

    /** Walks the whole chain from the start and reports the first event that doesn't add up. */
    public Check verify() {
        byte[] expectedPrev = GENESIS;
        long checked = 0;
        try (Stream<Event> events = jdbc.sql("select * from audit_event order by id").query(this::row).stream()) {
            for (Event e : (Iterable<Event>) events::iterator) {
                checked++;
                boolean linked = Arrays.equals(e.prevHash(), expectedPrev);
                boolean untouched = Arrays.equals(e.hash(), crypto.chainHash(e.prevHash(), e.requestId(), e.event(), e.detail(), e.at()));
                if (!linked || !untouched) return new Check(false, checked, e.id());
                expectedPrev = e.hash();
            }
        }
        return new Check(true, checked, null);
    }

    private Event row(ResultSet rs, int n) throws SQLException {
        return new Event(rs.getLong("id"), rs.getObject("request_id", UUID.class), rs.getString("event"),
                rs.getString("detail"), rs.getTimestamp("created_at").toInstant(),
                rs.getBytes("prev_hash"), rs.getBytes("hash"));
    }
}
