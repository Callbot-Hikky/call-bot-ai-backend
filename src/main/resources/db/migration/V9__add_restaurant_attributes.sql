-- Public-facing restaurant specifics (halal, terrace...) read by the AI during a call.
-- Free-form JSON: a restaurateur can declare anything without a schema change.
-- Separate from "settings", which holds internal application configuration.

ALTER TABLE restaurants ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb;
