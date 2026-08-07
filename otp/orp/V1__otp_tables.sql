-- Rate-limit state, one row per phone number.
-- Timestamps are epoch millis (BIGINT) to keep R2DBC mapping trivial.
CREATE TABLE otp_rate_limit (
    msisdn        VARCHAR(20) PRIMARY KEY,
    send_count    INT NOT NULL DEFAULT 0,
    window_start  BIGINT,      -- epoch millis when the current 24h window began (first send)
    last_sent_at  BIGINT,      -- epoch millis of last send
    blocked_until BIGINT       -- epoch millis when the 24h block ends
);

-- The active code, one row per phone number (overwritten on each new send).
CREATE TABLE otp_code (
    msisdn          VARCHAR(20) PRIMARY KEY,
    code_hash       VARCHAR(64) NOT NULL,   -- SHA-256 hex; use HMAC in production
    expires_at      BIGINT NOT NULL,        -- epoch millis
    verify_attempts INT NOT NULL DEFAULT 0
);
