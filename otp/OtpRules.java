package com.example.otp;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Pure rate-limit rules — no DB, no Spring, no clock of its own. Given the
 * current persisted state and the current time, it returns a {@link Decision}
 * (and, where relevant, the exact new state to persist). This is the piece
 * worth unit-testing exhaustively; {@link OtpService} just executes the result.
 *
 * Rules (rolling 24h window per number):
 *   send 1        -> immediate
 *   sends 2, 3    -> require 5 minutes since last send
 *   sends 4, 5, 6 -> require 15 minutes since last send
 *   attempt 7     -> refuse and start a 24h block (countdown starts here)
 */
public final class OtpRules {

    public static final Duration WINDOW = Duration.ofHours(24);
    public static final Duration BLOCK  = Duration.ofHours(24);
    public static final Duration TIER1  = Duration.ofMinutes(5);   // sends 2, 3
    public static final Duration TIER2  = Duration.ofMinutes(15);  // sends 4, 5, 6
    public static final int MAX_SENDS   = 6;

    private OtpRules() {}

    /** Current state as read from otp_rate_limit. Any field may be null on a fresh row. */
    public record RateRow(int count, OffsetDateTime windowStart,
                          OffsetDateTime lastSentAt, OffsetDateTime blockedUntil) {}

    /** State to persist back to otp_rate_limit (only produced for Allow / StartBlock). */
    public record RateState(int count, OffsetDateTime windowStart,
                            OffsetDateTime lastSentAt, OffsetDateTime blockedUntil) {}

    public sealed interface Decision {
        /** Permit the send and persist {@code newState}. */
        record Allow(int attempt, RateState newState) implements Decision {}
        /** Refuse (7th attempt) and persist {@code newState} carrying the new block. */
        record StartBlock(Duration retryAfter, RateState newState) implements Decision {}
        /** Refuse: inside an existing block. No state change. */
        record RejectBlocked(Duration retryAfter) implements Decision {}
        /** Refuse: tiered cooldown not elapsed. No state change. */
        record RejectCooldown(Duration retryAfter) implements Decision {}
    }

    public static Decision decide(RateRow row, OffsetDateTime now) {
        // 1. currently blocked?
        if (row.blockedUntil() != null && now.isBefore(row.blockedUntil())) {
            return new Decision.RejectBlocked(Duration.between(now, row.blockedUntil()));
        }

        // 2. rolling 24h window: reset if the first send was >= 24h ago (or no window yet)
        int count = row.count();
        OffsetDateTime windowStart = row.windowStart();
        if (windowStart == null || Duration.between(windowStart, now).compareTo(WINDOW) >= 0) {
            count = 0;
            windowStart = null;
        }

        int nextN = count + 1;

        // 3. seventh attempt -> start a 24h block and refuse
        if (nextN > MAX_SENDS) {
            OffsetDateTime until = now.plus(BLOCK);
            return new Decision.StartBlock(BLOCK,
                new RateState(count, windowStart, row.lastSentAt(), until));
        }

        // 4. tiered cooldown (send 1 free; 2,3 -> 5m; 4,5,6 -> 15m)
        Duration needed = cooldownFor(nextN);
        if (!needed.isZero() && row.lastSentAt() != null) {
            Duration since = Duration.between(row.lastSentAt(), now);
            if (since.compareTo(needed) < 0) {
                return new Decision.RejectCooldown(needed.minus(since));
            }
        }

        // 5. allow -> reserve one of the six
        OffsetDateTime newWindow = (count == 0) ? now : windowStart;
        return new Decision.Allow(nextN,
            new RateState(count + 1, newWindow, now, null));
    }

    /** Required gap before the n-th send of the window. */
    public static Duration cooldownFor(int n) {
        if (n <= 1) return Duration.ZERO;
        if (n <= 3) return TIER1;
        return TIER2;
    }
}
