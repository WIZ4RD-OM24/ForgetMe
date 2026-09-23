package dev.forgetme;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Counts how often each caller does something, in one-hour windows.
 * ponytail: counts live in memory, so several instances each count on their own. Redis if that stops being good enough.
 */
@Component
class RateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private record Count(Instant resetAt, AtomicInteger used) {}

    private final Map<String, Count> counts = new ConcurrentHashMap<>();
    private final int limit;

    RateLimiter(@Value("${forgetme.filings-per-hour}") int limit) {
        this.limit = limit;
    }

    boolean allow(String key, Instant now) {
        return record(key, now) <= limit;
    }

    /** Counts one event and says how many that key has had this hour. */
    int record(String key, Instant now) {
        if (counts.size() > MAX_TRACKED_KEYS) counts.values().removeIf(c -> now.isAfter(c.resetAt()));
        Count count = counts.compute(key, (k, existing) ->
                existing == null || now.isAfter(existing.resetAt()) ? new Count(now.plus(WINDOW), new AtomicInteger()) : existing);
        return count.used().incrementAndGet();
    }

    /** How many events this key has had this hour, without counting another. */
    int used(String key, Instant now) {
        Count count = counts.get(key);
        return count == null || now.isAfter(count.resetAt()) ? 0 : count.used().get();
    }
}
