package com.example.otp;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;

/**
 * OTP request + verify. All the rate-limit *rules* live in {@link OtpRules}
 * (pure, unit-tested); this class just reads the row under a Postgres lock,
 * asks OtpRules what to do, persists the result, and sends the SMS.
 *
 * Atomicity comes from a row lock (SELECT ... FOR UPDATE) inside a short,
 * I/O-free transaction — never held across the SMS network call.
 */
@Service
public class OtpService {

    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final int MAX_VERIFY   = 5;
    private static final SecureRandom RNG = new SecureRandom();

    private final DatabaseClient db;
    private final TransactionalOperator tx;
    private final SmsSender sms;

    public OtpService(DatabaseClient db, ReactiveTransactionManager tm, SmsSender sms) {
        this.db = db;
        this.tx = TransactionalOperator.create(tm);  // explicit boundary; avoids self-invocation traps
        this.sms = sms;
    }

    // ---- public API -------------------------------------------------------

    public Mono<Integer> request(String msisdn) {
        return reserve(msisdn).flatMap(gate -> switch (gate) {
            case OtpGate.Blocked b  -> Mono.<Integer>error(new OtpBlockedException(b.retryAfter()));
            case OtpGate.Cooldown c -> Mono.<Integer>error(new OtpCooldownException(c.retryAfter()));
            case OtpGate.Allowed a  -> issueAndSend(msisdn, a.attempt());
        });
    }

    // ---- reserve: read row under lock, apply OtpRules, persist -----------

    private Mono<OtpGate> reserve(String msisdn) {
        Mono<OtpGate> chain =
            db.sql("INSERT INTO otp_rate_limit(msisdn) VALUES(:m) ON CONFLICT (msisdn) DO NOTHING")
              .bind("m", msisdn).fetch().rowsUpdated()            // ensure a lockable row exists
              .then(db.sql("""
                    SELECT send_count, window_start, last_sent_at, blocked_until
                      FROM otp_rate_limit WHERE msisdn = :m FOR UPDATE
                    """)
                  .bind("m", msisdn)
                  .map((r, meta) -> new OtpRules.RateRow(
                      r.get("send_count", Integer.class),
                      r.get("window_start", OffsetDateTime.class),
                      r.get("last_sent_at", OffsetDateTime.class),
                      r.get("blocked_until", OffsetDateTime.class)))
                  .one())
              .flatMap(row -> apply(msisdn, row));
        return chain.as(tx::transactional);   // lock held only for this short, I/O-free tx
    }

    private Mono<OtpGate> apply(String msisdn, OtpRules.RateRow row) {
        OtpRules.Decision decision = OtpRules.decide(row, OffsetDateTime.now());
        return switch (decision) {
            case OtpRules.Decision.RejectBlocked r ->
                Mono.just(new OtpGate.Blocked(r.retryAfter()));
            case OtpRules.Decision.RejectCooldown r ->
                Mono.just(new OtpGate.Cooldown(r.retryAfter()));
            case OtpRules.Decision.StartBlock s ->
                persist(msisdn, s.newState()).thenReturn(new OtpGate.Blocked(s.retryAfter()));
            case OtpRules.Decision.Allow a ->
                persist(msisdn, a.newState()).thenReturn(new OtpGate.Allowed(a.attempt()));
        };
    }

    // ---- issue + send, OUTSIDE the lock; compensate on SMS failure -------

    private Mono<Integer> issueAndSend(String msisdn, int attempt) {
        String code = String.format("%06d", RNG.nextInt(1_000_000));
        return db.sql("""
                INSERT INTO otp_code(msisdn, code_hash, expires_at, verify_attempts)
                VALUES (:m, :h, :e, 0)
                ON CONFLICT (msisdn) DO UPDATE
                  SET code_hash = EXCLUDED.code_hash,
                      expires_at = EXCLUDED.expires_at,
                      verify_attempts = 0
                """)
            .bind("m", msisdn).bind("h", hash(code))
            .bind("e", OffsetDateTime.now().plus(OTP_TTL))
            .fetch().rowsUpdated()
            .then(sms.send(msisdn, "Your code is " + code + " (valid 5 min)"))
            .thenReturn(attempt)
            // SMS failed -> give the slot back so your outage doesn't cost the user
            .onErrorResume(err -> db.sql(
                    "UPDATE otp_rate_limit SET send_count = GREATEST(send_count-1,0) WHERE msisdn=:m")
                .bind("m", msisdn).fetch().rowsUpdated()
                .then(Mono.error(err)));
    }

    // ---- verify: attempt counting is atomic under FOR UPDATE -------------

    public Mono<VerifyOutcome> verify(String msisdn, String code) {
        Mono<VerifyOutcome> chain = db.sql("""
                SELECT code_hash, expires_at, verify_attempts
                  FROM otp_code WHERE msisdn = :m FOR UPDATE
                """)
            .bind("m", msisdn)
            .map((r, meta) -> new CodeRow(
                r.get("code_hash", String.class),
                r.get("expires_at", OffsetDateTime.class),
                r.get("verify_attempts", Integer.class)))
            .one()
            .flatMap(c -> {
                if (OffsetDateTime.now().isAfter(c.expiresAt())) {
                    return deleteCode(msisdn).thenReturn(VerifyOutcome.EXPIRED);
                }
                if (c.attempts() >= MAX_VERIFY) {
                    return deleteCode(msisdn).thenReturn(VerifyOutcome.TOO_MANY);
                }
                boolean ok = MessageDigest.isEqual(   // constant-time compare
                    c.codeHash().getBytes(UTF_8), hash(code).getBytes(UTF_8));
                if (ok) {
                    return deleteCode(msisdn).thenReturn(VerifyOutcome.OK);
                }
                return db.sql("UPDATE otp_code SET verify_attempts = verify_attempts+1 WHERE msisdn=:m")
                    .bind("m", msisdn).fetch().rowsUpdated()
                    .thenReturn(VerifyOutcome.INVALID);
            })
            .switchIfEmpty(Mono.just(VerifyOutcome.EXPIRED));  // no code row at all
        return chain.as(tx::transactional);
    }

    // ---- helpers ----------------------------------------------------------

    private Mono<Long> persist(String m, OtpRules.RateState s) {
        var spec = db.sql("""
            UPDATE otp_rate_limit
               SET send_count=:c, window_start=:ws, last_sent_at=:ls, blocked_until=:bu
             WHERE msisdn=:m
            """).bind("c", s.count()).bind("m", m);
        spec = bindNullable(spec, "ws", s.windowStart());
        spec = bindNullable(spec, "ls", s.lastSentAt());
        spec = bindNullable(spec, "bu", s.blockedUntil());
        return spec.fetch().rowsUpdated();
    }

    private Mono<Long> deleteCode(String m) {
        return db.sql("DELETE FROM otp_code WHERE msisdn=:m").bind("m", m).fetch().rowsUpdated();
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, OffsetDateTime v) {
        return v == null ? spec.bindNull(name, OffsetDateTime.class) : spec.bind(name, v);
    }

    /**
     * Hashes the code before storage. For real production, replace with
     * HMAC-SHA256(serverSecret, code): a 6-digit space is tiny and a plain
     * SHA-256 in a leaked DB is brute-forceable in microseconds.
     */
    private static String hash(String code) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(code.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    record CodeRow(String codeHash, OffsetDateTime expiresAt, int attempts) {}

    public enum VerifyOutcome { OK, INVALID, EXPIRED, TOO_MANY }
}
