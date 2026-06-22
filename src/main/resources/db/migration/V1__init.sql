-- Extensions required by the schema.
-- btree_gist allows GiST indexes over scalar types (e.g. uuid), which the
-- reservations EXCLUDE constraint relies on to forbid overlapping bookings.
CREATE EXTENSION IF NOT EXISTS btree_gist;
