ALTER TABLE checkout_payment
    ADD COLUMN provider_scope VARCHAR(160),
    ADD COLUMN creation_attempts INTEGER NOT NULL DEFAULT 0 CHECK (creation_attempts >= 0);
