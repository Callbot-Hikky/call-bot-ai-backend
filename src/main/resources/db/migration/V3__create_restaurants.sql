-- Restaurants (the primary tenant boundary) with their opening hours and tables.

CREATE TABLE restaurants (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    organization_id UUID         NOT NULL,
    name            VARCHAR(255) NOT NULL,
    phone_number    VARCHAR(32)  NOT NULL,
    address         VARCHAR(255),
    city            VARCHAR(120),
    postal_code     VARCHAR(20),
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'Europe/Paris',
    locale          VARCHAR(10)  NOT NULL DEFAULT 'fr',
    settings        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT uq_restaurants_phone_number UNIQUE (phone_number),
    CONSTRAINT fk_restaurants_organization FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

CREATE INDEX idx_restaurants_organization ON restaurants (organization_id);

CREATE TABLE restaurant_hours (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id UUID        NOT NULL,
    day_of_week   SMALLINT    NOT NULL,
    service       VARCHAR(16) NOT NULL,
    opens_at      TIME        NOT NULL,
    closes_at     TIME        NOT NULL,
    is_closed     BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_restaurant_hours_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT chk_restaurant_hours_day CHECK (day_of_week BETWEEN 0 AND 6),
    CONSTRAINT chk_restaurant_hours_service CHECK (service IN ('lunch', 'dinner'))
);

CREATE INDEX idx_restaurant_hours_restaurant ON restaurant_hours (restaurant_id);

CREATE TABLE tables (
    id            UUID        NOT NULL DEFAULT gen_random_uuid(),
    restaurant_id UUID        NOT NULL,
    name          VARCHAR(64) NOT NULL,
    capacity      INTEGER     NOT NULL,
    zone          VARCHAR(64),
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_tables_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT chk_tables_capacity CHECK (capacity > 0)
);

CREATE INDEX idx_tables_restaurant ON tables (restaurant_id);
