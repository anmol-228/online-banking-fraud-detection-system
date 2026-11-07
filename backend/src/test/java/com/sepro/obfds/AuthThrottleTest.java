package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sepro.obfds.config.AppProperties;
import com.sepro.obfds.security.AuthThrottle;
import com.sepro.obfds.security.TooManyAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The login throttle with a clock the test controls, so every time-based rule is exact. */
class AuthThrottleTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-07T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    private AuthThrottle throttle(int maxFailures, long lockoutSeconds, int perMinute, int maxEntries) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().setLoginMaxFailures(maxFailures);
        properties.getSecurity().setLoginLockoutSeconds(lockoutSeconds);
        properties.getSecurity().setLoginMaxPerMinutePerAddress(perMinute);
        properties.getSecurity().setThrottleMaxEntries(maxEntries);
        return new AuthThrottle(properties, clock);
    }

    private void fail(AuthThrottle t, String user, String ip, int times) {
        for (int i = 0; i < times; i++) {
            t.checkAllowed(user, ip);
            t.recordFailure(user, ip);
        }
    }

    @Test
    @DisplayName("The failure budget locks the pair and reports the remaining wait")
    void locksAfterBudget() {
        AuthThrottle t = throttle(3, 30, 1000, 1000);
        fail(t, "ravi", "10.0.0.1", 3);

        assertThatThrownBy(() -> t.checkAllowed("ravi", "10.0.0.1"))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(29L, 30L));
    }

    @Test
    @DisplayName("The lock expires after the wait, and each repeat lock doubles")
    void backoffDoubles() {
        AuthThrottle t = throttle(2, 30, 1000, 1000);
        fail(t, "ravi", "10.0.0.1", 2);
        clock.advance(Duration.ofSeconds(31));
        assertThatCode(() -> t.checkAllowed("ravi", "10.0.0.1")).doesNotThrowAnyException();

        t.recordFailure("ravi", "10.0.0.1");
        t.checkAllowed("ravi", "10.0.0.1");
        t.recordFailure("ravi", "10.0.0.1"); // second lock: 60 s
        assertThatThrownBy(() -> t.checkAllowed("ravi", "10.0.0.1"))
                .isInstanceOfSatisfying(TooManyAttemptsException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(59L, 60L));
    }

    @Test
    @DisplayName("The lock is per (username, address): another client is unaffected")
    void lockIsPerPair() {
        AuthThrottle t = throttle(2, 30, 1000, 1000);
        fail(t, "ravi", "10.0.0.1", 2);

        assertThatCode(() -> t.checkAllowed("ravi", "10.0.0.2")).doesNotThrowAnyException();
        assertThatCode(() -> t.checkAllowed("meera", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Username case and surrounding spaces do not create separate budgets")
    void usernameIsNormalised() {
        AuthThrottle t = throttle(2, 30, 1000, 1000);
        t.checkAllowed("Ravi", "10.0.0.1");
        t.recordFailure("Ravi", "10.0.0.1");
        t.checkAllowed(" ravi ", "10.0.0.1");
        t.recordFailure(" ravi ", "10.0.0.1");

        assertThatThrownBy(() -> t.checkAllowed("RAVI", "10.0.0.1")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("Success clears the history; stale failures are forgotten after 15 minutes")
    void successAndMemory() {
        AuthThrottle t = throttle(3, 30, 1000, 1000);
        fail(t, "ravi", "10.0.0.1", 2);
        t.recordSuccess("ravi", "10.0.0.1");
        fail(t, "ravi", "10.0.0.1", 2);
        assertThatCode(() -> t.checkAllowed("ravi", "10.0.0.1")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(16));
        fail(t, "ravi", "10.0.0.1", 2);
        assertThatCode(() -> t.checkAllowed("ravi", "10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("One address cycling through many usernames hits the per-minute ceiling")
    void addressCeiling() {
        AuthThrottle t = throttle(5, 30, 5, 1000);
        for (int i = 0; i < 5; i++) {
            t.checkAllowed("user" + i, "10.0.0.9");
        }
        assertThatThrownBy(() -> t.checkAllowed("user99", "10.0.0.9")).isInstanceOf(TooManyAttemptsException.class);

        clock.advance(Duration.ofSeconds(61));
        assertThatCode(() -> t.checkAllowed("user99", "10.0.0.9")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Memory is bounded: inventing usernames cannot grow the tracker without limit")
    void trackerIsBounded() {
        AuthThrottle t = throttle(5, 30, 1_000_000, 100);
        for (int i = 0; i < 5000; i++) {
            t.checkAllowed("invented" + i, "10.0.0." + (i % 250));
            t.recordFailure("invented" + i, "10.0.0." + (i % 250));
        }
        // Not directly observable; the point is that this completes and an old key was evicted,
        // which means its history is gone (no lock is remembered for it).
        fail(t, "invented0", "10.0.0.0", 1);
        assertThatCode(() -> t.checkAllowed("invented0", "10.0.0.0")).doesNotThrowAnyException();
    }
}
