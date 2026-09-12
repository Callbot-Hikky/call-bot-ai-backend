-- Anti-double-reservation sur TOUTES les tables d'une reservation, pas seulement la
-- table principale (reservations.table_id, contrainte EXCLUDE de V5/V7). Une reservation
-- repartie sur plusieurs tables ne peut plus voir une table secondaire reservee deux fois.
-- Triggers de contrainte differes : verifies a la validation, apres que l'ORM a ecrit la
-- reservation et ses tables, quel que soit l'ordre des ecritures.

CREATE OR REPLACE FUNCTION reservation_tables_check_overlap() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM reservations me
        JOIN reservation_tables other ON other.table_id = NEW.table_id
                                     AND other.reservation_id <> NEW.reservation_id
        JOIN reservations r ON r.id = other.reservation_id
        WHERE me.id = NEW.reservation_id
          AND me.status IN ('pending', 'confirmed', 'seated')
          AND r.status IN ('pending', 'confirmed', 'seated')
          AND tstzrange(r.starts_at, r.ends_at) && tstzrange(me.starts_at, me.ends_at)
    ) THEN
        RAISE EXCEPTION 'no_overlapping_reservation: table % is already booked on that slot', NEW.table_id
            USING ERRCODE = 'exclusion_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION reservations_check_move_overlap() RETURNS trigger AS $$
BEGIN
    IF NEW.status IN ('pending', 'confirmed', 'seated') AND EXISTS (
        SELECT 1
        FROM reservation_tables mine
        JOIN reservation_tables other ON other.table_id = mine.table_id
                                     AND other.reservation_id <> mine.reservation_id
        JOIN reservations r ON r.id = other.reservation_id
        WHERE mine.reservation_id = NEW.id
          AND r.status IN ('pending', 'confirmed', 'seated')
          AND tstzrange(r.starts_at, r.ends_at) && tstzrange(NEW.starts_at, NEW.ends_at)
    ) THEN
        RAISE EXCEPTION 'no_overlapping_reservation: reservation % overlaps another on one of its tables', NEW.id
            USING ERRCODE = 'exclusion_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_reservation_tables_no_overlap
    AFTER INSERT OR UPDATE ON reservation_tables
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION reservation_tables_check_overlap();

CREATE CONSTRAINT TRIGGER trg_reservations_move_no_overlap
    AFTER UPDATE OF starts_at, ends_at, status ON reservations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION reservations_check_move_overlap();
