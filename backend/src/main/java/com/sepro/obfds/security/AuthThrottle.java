package com.sepro.obfds.security;

import com.sepro.obfds.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing without letting the defence become the attack (SEC-4).
 *
 * <p>Two independent limits are applied <em>before</em> the password is checked, because checking
 * is the expensive part (a BCrypt hash costs tens of milliseconds of CPU):</p>
 * <ul>
 *   <li><b>Per (username, client address):</b> after a configured number of consecutive failures
 *       that pair is locked for a growing time (30 s, then 60 s, 120 s ... up to eight times the
 *       base). While locked, even the correct password is refused, so a guess cannot be "saved"
 *       for the last attempt. The lock is keyed on the pair on purpose: a per-username lock
 *       would let anyone on the internet lock a customer out by guessing their name.</li>
 *   <li><b>Per client address:</b> a ceiling on attempts of any kind per minute, which protects
 *       the server from a single client cycling through many usernames.</li>
 * </ul>
 *
 * <p>State is in memory and bounded: at most {@code throttleMaxEntries} keys are remembered and the
 * least recently used is dropped first, so an attacker cannot exhaust memory by inventing
 * usernames. A restart clears it, which is acceptable for a simulation and is documented. Behind a
 * proxy the "client address" is only meaningful when the proxy is trusted
 * ({@code server.forward-headers-strategy}); otherwise every client shares the proxy's address and
 * the address limit becomes a global limit.</p>
 */
@Component
public class AuthThrottle {

    private static final Duration FAILURE_MEMORY = Duration.ofMinutes(15);
    private static final int MAX_BACKOFF_DOUBLINGS = 3; // 30 s -> 240 s

    private final Clock clock;
    private final int maxFailures;
    private final Duration baseLockout;
    private final int perAddressPerMinute;
    private final int maxEntries;

    private final Map<String, PairState> pairs;
    private final Map<String, AddressState> addresses;

    public AuthThrottle(AppProperties properties, Clock clock) {
        AppProperties.Security config = properties.getSecurity();
        this.clock = clock;
        this.maxFailures = Math.max(1, config.getLoginMaxFailures());
        this.baseLockout = Duration.ofSeconds(Math.max(1, config.getLoginLockoutSeconds()));
        this.perAddressPerMinute = Math.max(1, config.getLoginMaxPerMinutePerAddress());
        this.maxEntries = Math.max(100, config.getThrottleMaxEntries());
        this.pairs = lru();
        this.addresses = lru();
    }

    private <V> Map<String, V> lru() {
        return new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /**
     * Counts this attempt against the address limit and refuses it if the pair is locked or the
     * address is over its ceiling.
     *
     * @throws TooManyAttemptsException with the number of seconds the caller should wait
     */
    public synchronized void checkAllowed(String username, String address) {
        Instant now = clock.instant();

        AddressState addressState = addresses.computeIfAbsent(address(address), key -> new AddressState(now));
        if (Duration.between(addressState.windowStart, now).compareTo(Duration.ofMinutes(1)) >= 0) {
            addressState.windowStart = now;
            addressState.count = 0;
        }
        addressState.count++;
        if (addressState.count > perAddressPerMinute) {
            long wait = Math.max(1, Duration.between(now, addressState.windowStart.plusSeconds(60)).toSeconds());
            throw new TooManyAttemptsException(wait);
        }

        PairState pair = pairs.get(pairKey(username, address));
        if (pair != null && pair.lockedUntil != null && now.isBefore(pair.lockedUntil)) {
            throw new TooManyAttemptsException(Math.max(1, Duration.between(now, pair.lockedUntil).toSeconds()));
        }
    }

    /** Records a rejected credential; locks the pair when the failure budget is spent. */
    public synchronized void recordFailure(String username, String address) {
        Instant now = clock.instant();
        PairState pair = pairs.computeIfAbsent(pairKey(username, address), key -> new PairState());
        if (pair.lastFailure != null && Duration.between(pair.lastFailure, now).compareTo(FAILURE_MEMORY) > 0) {
            pair.failures = 0;
            pair.lockouts = 0;
        }
        pair.lastFailure = now;
        pair.failures++;
        if (pair.failures >= maxFailures) {
            int doublings = Math.min(pair.lockouts, MAX_BACKOFF_DOUBLINGS);
            pair.lockedUntil = now.plus(baseLockout.multipliedBy(1L << doublings));
            pair.lockouts++;
            pair.failures = 0;
        }
    }

    /** A correct password clears the pair's history. */
    public synchronized void recordSuccess(String username, String address) {
        pairs.remove(pairKey(username, address));
    }

    private static String pairKey(String username, String address) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        return name + "|" + address(address);
    }

    private static String address(String address) {
        return address == null || address.isBlank() ? "unknown" : address;
    }

    private static final class PairState {
        int failures;
        int lockouts;
        Instant lastFailure;
        Instant lockedUntil;
    }

    private static final class AddressState {
        Instant windowStart;
        int count;

        AddressState(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}
