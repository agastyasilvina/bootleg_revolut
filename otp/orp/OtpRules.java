package com.example.otp;

import java.time.Duration;

/**
 * Pure rate-limit rules — no DB, no Spring. Timestamps are epoch millis
 * (System.currentTimeMillis()), which map cleanly through R2DBC as BIGINT and
 * avoid any TIMESTAMPTZ <-> OffsetDateTime conversion issues.
 *
 * Rules (rolling 24h window per number):
 *   send 1        -> immediate
 *   sends 2, 3    -> require 5 minutes since last send
 *   sends 4, 5, 6 -> require 15 minutes since last send
 *   attempt 7     -> refuse and start a 24h block (countdown starts here)
 */
public final class OtpRules {

    public static final long WINDOW_MS = Duration.ofHours(24).toMillis();
    public static final long BLOCK_MS  = Duration.ofHours(24).toMillis();
    public static final long TIER1_MS  = Duration.ofMinutes(5).toMillis();   // sends 2, 3
    public static final long TIER2_MS  = Duration.ofMinutes(15).toMillis();  // sends 4, 5, 6
    public static final int  MAX_SENDS = 6;

    private OtpRules() {}

    /** Current state from otp_rate_limit. Timestamps are epoch millis; may be null on a fresh row. */
    public record RateRow(int count, Long windowStart, Long lastSentAt, Long blockedUntil) {}

    /** State to persist back (only for Allow / StartBlock). */
    public record RateState(int count, Long windowStart, Long lastSentAt, Long blockedUntil) {}

    public sealed interface Decision {
        record Allow(int attempt, RateState newState) implements Decision {}
        record StartBlock(Duration retryAfter, RateState newState) implements Decision {}
        record RejectBlocked(Duration retryAfter) implements Decision {}
        record RejectCooldown(Duration retryAfter) implements Decision {}
    }

    public static Decision decide(RateRow row, long now) {
        // 1. currently blocked?
        if (row.blockedUntil() != null && now < row.blockedUntil()) {
            return new Decision.RejectBlocked(Duration.ofMillis(row.blockedUntil() - now));
        }

        // 2. rolling 24h window: reset if the first send was >= 24h ago (or no window yet)
        int count = row.count();
        Long windowStart = row.windowStart();
        if (windowStart == null || now - windowStart >= WINDOW_MS) {
            count = 0;
            windowStart = null;
        }

        int nextN = count + 1;

        // 3. seventh attempt -> start a 24h block and refuse
        if (nextN > MAX_SENDS) {
            long until = now + BLOCK_MS;
            return new Decision.StartBlock(Duration.ofMillis(BLOCK_MS),
                new RateState(count, windowStart, row.lastSentAt(), until));
        }

        // 4. tiered cooldown (send 1 free; 2,3 -> 5m; 4,5,6 -> 15m)
        long needed = cooldownMillisFor(nextN);
        if (needed > 0 && row.lastSentAt() != null) {
            long since = now - row.lastSentAt();
            if (since < needed) {
                return new Decision.RejectCooldown(Duration.ofMillis(needed - since));
            }
        }

        // 5. allow -> reserve one of the six
        Long newWindow = (count == 0) ? Long.valueOf(now) : windowStart;
        return new Decision.Allow(nextN,
            new RateState(count + 1, newWindow, now, null));
    }

    /** Required gap (millis) before the n-th send of the window. */
    public static long cooldownMillisFor(int n) {
        if (n <= 1) return 0L;
        if (n <= 3) return TIER1_MS;
        return TIER2_MS;
    }
}
