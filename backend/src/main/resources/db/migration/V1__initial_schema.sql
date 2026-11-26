CREATE TABLE app_user (
                          id UUID PRIMARY KEY,
                          email VARCHAR(254) NOT NULL UNIQUE,
                          display_name VARCHAR(160) NOT NULL,
                          password_hash VARCHAR(100) NOT NULL,
                          enabled BOOLEAN NOT NULL DEFAULT TRUE,
                          password_change_required BOOLEAN NOT NULL DEFAULT TRUE,
                          failed_login_attempts INTEGER NOT NULL DEFAULT 0,
                          locked_until TIMESTAMPTZ,
                          created_at TIMESTAMPTZ NOT NULL,
                          updated_at TIMESTAMPTZ NOT NULL,
                          version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE app_user_role (
                               user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
                               role VARCHAR(32) NOT NULL,
                               PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_session (
                                 id UUID PRIMARY KEY,
                                 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
                                 token_hash VARCHAR(64) NOT NULL UNIQUE,
                                 expires_at TIMESTAMPTZ NOT NULL,
                                 revoked_at TIMESTAMPTZ,
                                 replaced_by UUID,
                                 user_agent VARCHAR(500),
                                 ip_address VARCHAR(64),
                                 created_at TIMESTAMPTZ NOT NULL,
                                 last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_refresh_user
    ON refresh_session(user_id);

CREATE INDEX idx_refresh_expires
    ON refresh_session(expires_at);


CREATE TABLE vinyl_record (
                             id UUID PRIMARY KEY,
                             code VARCHAR(50) NOT NULL UNIQUE,
                             name VARCHAR(200),
                             release_year INTEGER,
                             active BOOLEAN NOT NULL DEFAULT TRUE,
                             created_at TIMESTAMPTZ NOT NULL,
                             updated_at TIMESTAMPTZ NOT NULL,
                             version BIGINT NOT NULL DEFAULT 0
);


CREATE TABLE purchase_order (
                         id UUID PRIMARY KEY,
                         external_id VARCHAR(100) UNIQUE,
                         album_title VARCHAR(100) NOT NULL,
                         artist VARCHAR(100) NOT NULL,
                         customer_name VARCHAR(100),
                         email VARCHAR(254),
                         phone VARCHAR(32),
                         ordered_on DATE,
                         record_id UUID REFERENCES vinyl_record(id),
                         status VARCHAR(32) NOT NULL,

    -- Сумма заказа в рублях.
                         total_price NUMERIC(12,2),

                         paid BOOLEAN NOT NULL DEFAULT FALSE,
                         priority_level VARCHAR(32) NOT NULL,
                         archived BOOLEAN NOT NULL DEFAULT FALSE,
                         archived_at TIMESTAMPTZ,
                         archived_by UUID REFERENCES app_user(id),
                         created_by UUID REFERENCES app_user(id),
                         updated_by UUID REFERENCES app_user(id),
                         created_at TIMESTAMPTZ NOT NULL,
                         updated_at TIMESTAMPTZ NOT NULL,
                         version BIGINT NOT NULL DEFAULT 0,

                         CONSTRAINT chk_order_total_price
                             CHECK (
                                 total_price IS NULL
                                     OR (total_price >= 0.00 AND total_price <= 9999999999.99)
                                 )
);

COMMENT ON COLUMN purchase_order.total_price
    IS 'Сумма заказа в рублях';

CREATE INDEX idx_order_name
    ON purchase_order(artist, album_title, customer_name);

CREATE INDEX idx_order_record
    ON purchase_order(record_id);

CREATE INDEX idx_order_status
    ON purchase_order(status);

CREATE INDEX idx_order_archived
    ON purchase_order(archived);

CREATE INDEX idx_order_priority
    ON purchase_order(priority_level);


CREATE TABLE order_tag (
                             order_id UUID NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
                             tag VARCHAR(50) NOT NULL,
                             PRIMARY KEY (order_id, tag)
);

CREATE INDEX idx_order_tag_value
    ON order_tag(tag);


CREATE TABLE order_note (
                              id UUID PRIMARY KEY,
                              order_id UUID NOT NULL REFERENCES purchase_order(id) ON DELETE CASCADE,
                              author_id UUID NOT NULL REFERENCES app_user(id),
                              body VARCHAR(4000) NOT NULL,
                              pinned BOOLEAN NOT NULL DEFAULT FALSE,
                              created_at TIMESTAMPTZ NOT NULL,
                              updated_at TIMESTAMPTZ NOT NULL,
                              version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_note_order
    ON order_note(order_id, pinned DESC, created_at DESC);


CREATE TABLE audit_event (
                             id UUID PRIMARY KEY,
                             actor_id UUID,
                             actor_email VARCHAR(254),
                             event_type VARCHAR(80) NOT NULL,
                             entity_type VARCHAR(80),
                             entity_id UUID,
                             summary VARCHAR(1000) NOT NULL,
                             correlation_id VARCHAR(100),
                             ip_address VARCHAR(64),
                             user_agent VARCHAR(500),
                             occurred_at TIMESTAMPTZ NOT NULL,
                             details_json TEXT
);

CREATE INDEX idx_audit_occurred
    ON audit_event(occurred_at DESC);

CREATE INDEX idx_audit_actor
    ON audit_event(actor_email);

CREATE INDEX idx_audit_entity
    ON audit_event(entity_type, entity_id);
