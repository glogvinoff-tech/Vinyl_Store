ALTER TABLE checkout_payment ADD COLUMN return_url VARCHAR(2048);
ALTER TABLE purchase_order ALTER COLUMN album_title TYPE VARCHAR(200);

CREATE INDEX idx_checkout_payment_owner_status
    ON checkout_payment(user_id, status, created_at);

CREATE INDEX idx_checkout_payment_status_id ON checkout_payment(status, id);
