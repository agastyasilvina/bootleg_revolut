package com.example.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.example.otp.OtpRules.Decision;
import com.example.otp.OtpRules.RateRow;

/**
 * Pure unit tests for the rate-limit rules. No DB, no Spring, no mocks — a
 * fixed {@code NOW} (epoch millis) keeps every assertion exact.
 */
class OtpRulesTest {

    private static final long NOW = 1_700_000_000_000L;  // fixed reference instant

    private static long minsAgo(long m)  { return NOW - m * 60_000L; }
    private static long hoursAgo(long h)  { return NOW - h * 3_600_000L; }
    private static long minsAhead(long m) { return NOW + m * 60_000L; }
    private static long hoursAhead(long h) { return NOW + h * 3_600_000L; }

    private static RateRow row(int count, Long windowStart, Long lastSent, Long blockedUntil) {
        return new RateRow(count, windowStart, lastSent, blockedUntil);
    }

    // ---- first send -------------------------------------------------------

    @Test
    @DisplayName("fresh row -> Allow(1), window opens now")
    void firstSendAllowed() {
        Decision d = OtpRules.decide(row(0, null, null, null), NOW);

        assertThat(d).isInstanceOfSatisfying(Decision.Allow.class, a -> {
            assertThat(a.attempt()).isEqualTo(1);
            assertThat(a.newState().count()).isEqualTo(1);
            assertThat(a.newState().windowStart()).isEqualTo(NOW);
            assertThat(a.newState().lastSentAt()).isEqualTo(NOW);
            assertThat(a.newState().blockedUntil()).isNull();
        });
    }

    // ---- tier 1: sends 2 and 3 need 5 minutes -----------------------------

    @Nested
    @DisplayName("tier 1 (sends 2 & 3, 5-minute gap)")
    class Tier1 {

        @Test
        @DisplayName("send 2 too soon -> Cooldown with the remaining time")
        void send2TooSoon() {
            // last send 4 minutes ago, need 5 -> 1 minute left
            Decision d = OtpRules.decide(row(1, minsAgo(10), minsAgo(4), null), NOW);

            assertThat(d).isInstanceOfSatisfying(Decision.RejectCooldown.class,
                c -> assertThat(c.retryAfter()).isEqualTo(Duration.ofMinutes(1)));
        }

        @Test
        @DisplayName("send 2 exactly at 5 minutes -> Allow (boundary is inclusive)")
        void send2AtBoundary() {
            Decision d = OtpRules.decide(row(1, minsAgo(10), minsAgo(5), null), NOW);

            assertThat(d).isInstanceOfSatisfying(Decision.Allow.class,
                a -> assertThat(a.attempt()).isEqualTo(2));
        }

        @Test
        @DisplayName("send 3 after 5 minutes -> Allow(3), window unchanged")
        void send3Allowed() {
            long windowStart = minsAgo(20);
            Decision d = OtpRules.decide(row(2, windowStart, minsAgo(6), null), NOW);

            assertThat(d).isInstanceOfSatisfying(Decision.Allow.class, a -> {
                assertThat(a.attempt()).isEqualTo(3);
                assertThat(a.newState().count()).isEqualTo(3);
                assertThat(a.newState().windowStart()).isEqualTo(windowStart);
                assertThat(a.newState().lastSentAt()).isEqualTo(NOW);
            });
        }
    }

    // ---- tier 2: sends 4, 5, 6 need 15 minutes ----------------------------

    @Nested
    @DisplayName("tier 2 (sends 4-6, 15-minute gap)")
    class Tier2 {

        @Test
        @DisplayName("send 4 within 15 minutes -> Cooldown")
        void send4TooSoon() {
            // 10 minutes since last, need 15 -> 5 left
            Decision d = OtpRules.decide(row(3, hoursAgo(1), minsAgo(10), null), NOW);

            assertThat(d).isInstanceOfSatisfying(Decision.RejectCooldown.class,
                c -> assertThat(c.retryAfter()).isEqualTo(Duration.ofMinutes(5)));
        }

        @Test
        @DisplayName("send 4 after 15 minutes -> Allow(4)")
        void send4Allowed() {
            Decision d = OtpRules.decide(row(3, hoursAgo(1), minsAgo(15), null), NOW);

            assertThat(d).isInstanceOfSatisfying(Decision.Allow.class,
                a -> assertThat(a.attempt()).isEqualTo(4));
        }

        @Test
        @DisplayName("send 6 after 15 minutes -> Allow(6), count reaches the cap")
        void send6Allowed() {
            Decision d = OtpRules.decide(row(5, hoursAgo(2), minsAgo(20), null), NOW);

            assertThat(d).isInstanceOfSatisfying(Decision.Allow.class, a -> {
                assertThat(a.attempt()).isEqualTo(6);
                assertThat(a.newState().count()).isEqualTo(6);
            });
        }
    }

    // ---- the 7th attempt starts the block ---------------------------------

    @Test
    @DisplayName("7th attempt -> StartBlock for 24h, countdown starts now")
    void seventhAttemptBlocks() {
        long windowStart = hoursAgo(3);
        Decision d = OtpRules.decide(row(6, windowStart, minsAgo(20), null), NOW);

        assertThat(d).isInstanceOfSatisfying(Decision.StartBlock.class, s -> {
            assertThat(s.retryAfter()).isEqualTo(Duration.ofHours(24));
            assertThat(s.newState().count()).isEqualTo(6);                 // count not bumped
            assertThat(s.newState().windowStart()).isEqualTo(windowStart);
            assertThat(s.newState().blockedUntil()).isEqualTo(NOW + OtpRules.BLOCK_MS);
        });
    }

    // ---- already blocked --------------------------------------------------

    @Test
    @DisplayName("inside an active block -> RejectBlocked with time remaining")
    void activeBlockRejected() {
        Decision d = OtpRules.decide(row(6, hoursAgo(5), hoursAgo(2), hoursAhead(2)), NOW);

        assertThat(d).isInstanceOfSatisfying(Decision.RejectBlocked.class,
            b -> assertThat(b.retryAfter()).isEqualTo(Duration.ofHours(2)));
    }

    // ---- window / block expiry --------------------------------------------

    @Test
    @DisplayName("window older than 24h -> counter resets, next send is Allow(1)")
    void windowResets() {
        Decision d = OtpRules.decide(row(6, hoursAgo(25), hoursAgo(25), null), NOW);

        assertThat(d).isInstanceOfSatisfying(Decision.Allow.class, a -> {
            assertThat(a.attempt()).isEqualTo(1);
            assertThat(a.newState().count()).isEqualTo(1);
            assertThat(a.newState().windowStart()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("expired block + stale window -> treated as a fresh Allow(1)")
    void expiredBlockAllowsAgain() {
        Decision d = OtpRules.decide(row(6, hoursAgo(25), hoursAgo(25), hoursAgo(1)), NOW);

        assertThat(d).isInstanceOfSatisfying(Decision.Allow.class,
            a -> assertThat(a.attempt()).isEqualTo(1));
    }

    // ---- cooldownMillisFor mapping ---------------------------------------

    @ParameterizedTest(name = "send #{0} -> {1}ms cooldown")
    @CsvSource({
        "1, 0",        // first send is free
        "2, 300000",   // 5 min
        "3, 300000",   // 5 min
        "4, 900000",   // 15 min
        "5, 900000",   // 15 min
        "6, 900000"    // 15 min
    })
    @DisplayName("cooldownMillisFor returns the right gap per send number")
    void cooldownForMapping(int n, long expectedMillis) {
        assertThat(OtpRules.cooldownMillisFor(n)).isEqualTo(expectedMillis);
    }
}
