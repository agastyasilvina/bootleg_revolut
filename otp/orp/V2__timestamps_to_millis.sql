-- Only needed if you already applied the original TIMESTAMPTZ version of V1
-- and want to keep existing rows. Converts the timestamp columns to BIGINT
-- epoch millis. If you wiped the DB and re-ran the (now BIGINT) V1, skip this.
ALTER TABLE otp_rate_limit
    ALTER COLUMN window_start  TYPE BIGINT USING (EXTRACT(EPOCH FROM window_start)  * 1000)::BIGINT,
    ALTER COLUMN last_sent_at  TYPE BIGINT USING (EXTRACT(EPOCH FROM last_sent_at)  * 1000)::BIGINT,
    ALTER COLUMN blocked_until TYPE BIGINT USING (EXTRACT(EPOCH FROM blocked_until) * 1000)::BIGINT;

ALTER TABLE otp_code
    ALTER COLUMN expires_at TYPE BIGINT USING (EXTRACT(EPOCH FROM expires_at) * 1000)::BIGINT;
