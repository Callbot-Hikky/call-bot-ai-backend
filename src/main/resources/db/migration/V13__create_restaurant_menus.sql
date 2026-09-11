-- Menu du restaurant, un par restaurant (meme pattern que floor_plans).
-- Le contenu saisi a la main est un document JSON opaque appartenant au front
-- ({ version, sections }). Les fichiers (PDF, images) sont stockes en base.
CREATE TABLE restaurant_menus (
    restaurant_id  UUID        PRIMARY KEY,
    mode           TEXT        NOT NULL DEFAULT 'none',
    manual_content JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_restaurant_menus_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT chk_restaurant_menus_mode
        CHECK (mode IN ('none', 'pdf', 'images', 'manual'))
);

CREATE TABLE restaurant_menu_files (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID        NOT NULL,
    kind          TEXT        NOT NULL,
    position      INT         NOT NULL DEFAULT 0,
    content_type  TEXT        NOT NULL,
    size_bytes    BIGINT      NOT NULL,
    data          BYTEA       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_restaurant_menu_files_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE,
    CONSTRAINT chk_restaurant_menu_files_kind CHECK (kind IN ('pdf', 'image')),
    CONSTRAINT chk_restaurant_menu_files_size CHECK (size_bytes > 0)
);

CREATE INDEX idx_restaurant_menu_files_restaurant
    ON restaurant_menu_files (restaurant_id, position);
