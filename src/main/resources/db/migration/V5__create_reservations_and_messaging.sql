-- Reservations (the core of the product), outbound SMS and GDPR consents.

CREATE TABLE reservations (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id UUID         NOT NULL,
    customer_id   UUID,
    table_id      UUID,
    call_id       UUID,
    starts_at     TIMESTAMPTZ  NOT NULL,
    ends_at       TIMESTAMPTZ  NOT NULL,
    party_size    INTEGER      NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'pending',
    source        VARCHAR(16)  NOT NULL DEFAULT 'callbot',
    notes         TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cancelled_at  TIMESTAMPTZ,
    PRIMARY KEY (id),
    CONSTRAINT fk_reservations_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservations_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL,
    CONSTRAINT fk_reservations_table FOREIGN KEY (table_id) REFERENCES tables (id) ON DELETE SET NULL,
    CONSTRAINT fk_reservations_call FOREIGN KEY (call_id) REFERENCES calls (id) ON DELETE SET NULL,
    CONSTRAINT chk_reservations_party_size CHECK (party_size > 0),
    CONSTRAINT chk_reservations_time CHECK (ends_at > starts_at),
    CONSTRAINT chk_reservations_status CHECK (status IN ('pending', 'confirmed', 'seated', 'completed', 'cancelled', 'no_show')),
    CONSTRAINT chk_reservations_source CHECK (source IN ('callbot', 'manual', 'web'))
);

CREATE INDEX idx_reservations_restaurant_starts ON reservations (restaurant_id, starts_at);

-- A table can never hold two reservations whose time ranges overlap.
-- Cancelled reservations and rows without an assigned table are exempt.
ALTER TABLE reservations ADD CONSTRAINT no_overlapping_reservation
    EXCLUDE USING gist (
        table_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (table_id IS NOT NULL AND status <> 'cancelled');

CREATE TABLE messages (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id      UUID         NOT NULL,
    customer_id        UUID,
    reservation_id     UUID,
    twilio_message_sid VARCHAR(64),
    to_number          VARCHAR(32)  NOT NULL,
    from_number        VARCHAR(32),
    body               TEXT         NOT NULL,
    trigger_type       VARCHAR(32)  NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'queued',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at            TIMESTAMPTZ,
    PRIMARY KEY (id),
    CONSTRAINT fk_messages_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL,
    CONSTRAINT fk_messages_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE SET NULL,
    CONSTRAINT chk_messages_status CHECK (status IN ('queued', 'sent', 'delivered', 'failed'))
);

CREATE INDEX idx_messages_restaurant_created ON messages (restaurant_id, created_at);

CREATE TABLE consents (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id  UUID         NOT NULL,
    customer_id    UUID,
    customer_phone VARCHAR(32)  NOT NULL,
    type           VARCHAR(32)  NOT NULL,
    granted        BOOLEAN      NOT NULL,
    granted_at     TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    source         VARCHAR(16),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_consents_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT fk_consents_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE SET NULL,
    CONSTRAINT chk_consents_type CHECK (type IN ('call_recording', 'data_processing'))
);

CREATE INDEX idx_consents_restaurant ON consents (restaurant_id);
