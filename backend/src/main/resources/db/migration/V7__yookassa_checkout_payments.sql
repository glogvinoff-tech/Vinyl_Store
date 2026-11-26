CREATE TABLE checkout_payment (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    provider_payment_id VARCHAR(64) UNIQUE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL,
    confirmation_url VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

ALTER TABLE purchase_order
    ADD COLUMN checkout_payment_id UUID REFERENCES checkout_payment(id);

CREATE INDEX idx_purchase_order_checkout_payment
    ON purchase_order(checkout_payment_id);
