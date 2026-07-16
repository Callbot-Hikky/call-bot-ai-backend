-- Floor plan layout: visual arrangement of a restaurant's room (table geometry,
-- walls). One plan per restaurant. The layout is an opaque JSON document owned
-- by the frontend editor ({ version, geometry, walls }): the backend stores and
-- serves it verbatim, business data (tables, reservations) stays relational.

CREATE TABLE floor_plans (
    restaurant_id UUID        NOT NULL,
    layout        JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (restaurant_id),
    CONSTRAINT fk_floor_plans_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
);
