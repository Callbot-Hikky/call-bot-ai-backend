-- Customer records and call journaling (calls feed reservations and callbacks).

CREATE TABLE customers (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id UUID         NOT NULL,
    phone         VARCHAR(32)  NOT NULL,
    first_name    VARCHAR(100),
    last_name     VARCHAR(100),
    email         VARCHAR(255),
    notes         TEXT,
    tags          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uq_customers_restaurant_phone UNIQUE (restaurant_id, phone),
    CONSTRAINT fk_customers_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);

CREATE INDEX idx_customers_restaurant ON customers (restaurant_id);

CREATE TABLE calls (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id    UUID         NOT NULL,
    customer_id      UUID,
    twilio_call_sid  VARCHAR(64)  NOT NULL,
    from_number      VARCHAR(32),
    to_number        VARCHAR(32),
    direction        VARCHAR(16)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    outcome          VARCHAR(32),
    captured         BOOLEAN      NOT NULL DEFAULT FALSE,
    started_at       TIMESTAMPTZ,
    ended_at         TIMESTAMPTZ,
    duration_seconds INTEGER,
    recording_url    TEXT,
    transcript       TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uq_calls_twilio_sid UNIQUE (twilio_call_sid),
    CONSTRAINT fk_calls_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_calls_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL,
    CONSTRAINT chk_calls_direction CHECK (direction IN ('inbound', 'outbound')),
    CONSTRAINT chk_calls_status CHECK (status IN ('in_progress', 'completed', 'missed', 'transferred', 'failed')),
    CONSTRAINT chk_calls_outcome CHECK (outcome IN ('reservation_created', 'callback_requested', 'info_provided', 'transferred_human', 'abandoned'))
);

CREATE INDEX idx_calls_restaurant_started ON calls (restaurant_id, started_at);

CREATE TABLE callback_requests (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id  UUID         NOT NULL,
    call_id        UUID         NOT NULL,
    customer_phone VARCHAR(32)  NOT NULL,
    customer_name  VARCHAR(200),
    reason         TEXT,
    status         VARCHAR(32)  NOT NULL DEFAULT 'pending',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ,
    PRIMARY KEY (id),
    CONSTRAINT fk_callback_requests_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_callback_requests_call FOREIGN KEY (call_id) REFERENCES calls (id) ON DELETE CASCADE,
    CONSTRAINT chk_callback_requests_status CHECK (status IN ('pending', 'called_back', 'resolved', 'cancelled'))
);

CREATE INDEX idx_callback_requests_restaurant ON callback_requests (restaurant_id);
