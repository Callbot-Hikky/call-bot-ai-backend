-- Le créneau d'une table n'est réservé que par les réservations ACTIVES.
-- V5 n'exemptait que 'cancelled' : une réservation 'completed' (service terminé)
-- ou 'no_show' continuait de bloquer la table pour sa plage horaire, ce qui
-- provoquait un 409 en installant un walk-in sur une table pourtant libérée.
-- On aligne la contrainte sur ce que l'UI considère comme "libre".
ALTER TABLE reservations DROP CONSTRAINT no_overlapping_reservation;

ALTER TABLE reservations ADD CONSTRAINT no_overlapping_reservation
    EXCLUDE USING gist (
        table_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (table_id IS NOT NULL AND status IN ('pending', 'confirmed', 'seated'));
