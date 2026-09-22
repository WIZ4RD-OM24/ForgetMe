package dev.forgetme;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter(3);
    private final Instant now = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void allowsUpToTheLimitThenStops() {
        for (int i = 0; i < 3; i++) assertTrue(limiter.allow("1.2.3.4", now));
        assertFalse(limiter.allow("1.2.3.4", now));
    }

    @Test
    void countsEachCallerSeparatelyAndForgetsAfterAnHour() {
        for (int i = 0; i < 4; i++) limiter.allow("1.2.3.4", now);
        assertTrue(limiter.allow("5.6.7.8", now), "someone else isn't affected");
        assertTrue(limiter.allow("1.2.3.4", now.plus(Duration.ofHours(1)).plusSeconds(1)), "a new hour starts fresh");
    }
}
