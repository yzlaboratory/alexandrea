package dev.yzlaboratory.alexandrea.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-provider circuit breaker (ADR 0015): after {@link #FAILURE_THRESHOLD}
 * consecutive upstream failures for a given provider, short-circuits every
 * call to that provider for {@link #OPEN_DURATION}, then half-opens and lets
 * exactly one call through as a probe — a successful probe closes the
 * breaker, a failed one re-opens it for another {@link #OPEN_DURATION}.
 *
 * <p>One instance covers every provider, keyed by provider name in an
 * internal map, so today's single TMDB caller and slice 3's OpenLibrary/IGDB
 * clients share this bean without per-provider wiring. State is in-process
 * only, the same shape as the auth module's
 * {@code RateLimiter}/{@code RateLimitBucketStore} pair minus the SQLite
 * persistence — ADR 0015 wants the breaker to reset closed on every restart,
 * so there's nothing to persist.
 */
@Component
public class ProviderCircuitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCircuitBreaker.class);

    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(60);

    private final Clock clock;
    private final ConcurrentHashMap<String, ProviderState> states = new ConcurrentHashMap<>();

    public ProviderCircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    /**
     * Whether a call to {@code provider} should be attempted right now.
     * True when closed, or when the open window has elapsed and this caller
     * claims the single half-open probe; false while open, or when another
     * caller has already claimed that probe.
     */
    public boolean allowRequest(String provider) {
        return stateFor(provider).allowRequest(provider, clock.instant());
    }

    /** Reports that a request {@link #allowRequest} admitted succeeded. */
    public void recordSuccess(String provider) {
        stateFor(provider).recordSuccess(provider);
    }

    /** Reports that a request {@link #allowRequest} admitted failed. */
    public void recordFailure(String provider) {
        stateFor(provider).recordFailure(provider, clock.instant());
    }

    private ProviderState stateFor(String provider) {
        return states.computeIfAbsent(provider, ignoredKey -> new ProviderState());
    }

    /**
     * One provider's breaker state. Methods are {@code synchronized} rather
     * than built from separate atomic fields: a transition touches the
     * failure count, the opened-at timestamp, and the probe-claimed flag
     * together, and a monitor keeps that group atomic far more simply than a
     * compare-and-swap loop over a composite value would.
     */
    private static final class ProviderState {
        private int consecutiveFailures;
        private Instant openedAt;
        private boolean probeClaimed;

        synchronized boolean allowRequest(String provider, Instant now) {
            if (openedAt == null) {
                return true;
            }
            if (now.isBefore(openedAt.plus(OPEN_DURATION))) {
                return false;
            }
            if (probeClaimed) {
                return false;
            }
            probeClaimed = true;
            LOG.info("Circuit breaker for {} half-open; admitting one probe", provider);
            return true;
        }

        synchronized void recordSuccess(String provider) {
            // A closed-state call can still be in flight when a *different*
            // concurrent failure trips the breaker open; if that straggler
            // then succeeds, it is not the claimed half-open probe succeeding
            // — ignore it rather than closing a breaker that is still
            // rightfully open (openedAt != null but probeClaimed is false).
            if (openedAt != null && !probeClaimed) {
                return;
            }
            var isClosingFromOpen = openedAt != null;
            consecutiveFailures = 0;
            openedAt = null;
            probeClaimed = false;
            if (isClosingFromOpen) {
                LOG.info("Circuit breaker for {} closed after a successful probe", provider);
            }
        }

        synchronized void recordFailure(String provider, Instant now) {
            if (openedAt != null) {
                // Only the claimed half-open probe failing re-opens the
                // window for another OPEN_DURATION. A straggler failure from
                // a closed-state call that was already in flight when some
                // other concurrent failure opened the breaker is not that
                // probe (probeClaimed is false for it) and must not keep
                // pushing the open window forward indefinitely.
                if (probeClaimed) {
                    openedAt = now;
                    probeClaimed = false;
                    LOG.warn("Circuit breaker for {} re-opened after a failed probe", provider);
                }
                return;
            }
            consecutiveFailures++;
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                openedAt = now;
                LOG.warn("Circuit breaker for {} opened after {} consecutive failures", provider, consecutiveFailures);
            }
        }
    }
}
