CREATE TABLE email_challenge (
 email VARCHAR(254) PRIMARY KEY, purpose VARCHAR(20) NOT NULL,
 code_hash VARCHAR(100) NOT NULL, password_hash VARCHAR(100), display_name VARCHAR(160),
 expires_at TIMESTAMPTZ NOT NULL, sent_at TIMESTAMPTZ NOT NULL,
 window_start TIMESTAMPTZ NOT NULL, send_count INTEGER NOT NULL,
 attempts INTEGER NOT NULL, consumed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_email_challenge_expiry ON email_challenge(expires_at);
