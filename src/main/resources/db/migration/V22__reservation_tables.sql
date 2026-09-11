-- Réservations multi-tables : un groupe (jusqu'à 15 personnes) peut occuper
-- plusieurs tables lorsqu'aucune table unique n'est assez grande
-- (ex. 15 = table de 8 + table de 4 + table de 4).
--
-- Modèle : `reservations.table_id` reste la table PRINCIPALE (compat dashboard
-- + contrainte anti-double-booking `no_overlapping_reservation` inchangée), et
-- cette table de liaison porte l'ENSEMBLE des tables d'une réservation
-- (principale incluse). La détection d'occupation (availability) s'appuie
-- désormais sur cette table de liaison, qui couvre donc toutes les tables.

CREATE TABLE reservation_tables (
    reservation_id UUID NOT NULL,
    table_id       UUID NOT NULL,
    PRIMARY KEY (reservation_id, table_id),
    CONSTRAINT fk_reservation_tables_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (id) ON DELETE CASCADE,
    CONSTRAINT fk_reservation_tables_table
        FOREIGN KEY (table_id) REFERENCES tables (id) ON DELETE CASCADE
);

CREATE INDEX idx_reservation_tables_table ON reservation_tables (table_id);

-- Reprise de l'existant : chaque réservation mono-table déjà en base devient
-- une ligne de liaison, pour que la détection d'occupation reste correcte.
INSERT INTO reservation_tables (reservation_id, table_id)
SELECT id, table_id
FROM reservations
WHERE table_id IS NOT NULL;
