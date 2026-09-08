-- Réservations payantes : mode de garantie par restaurant, et garantie portée
-- par chaque réservation.

-- 1. Réglage du restaurant ---------------------------------------------------

ALTER TABLE restaurants
    ADD COLUMN guarantee_mode                  VARCHAR(32) NOT NULL DEFAULT 'none',
    ADD COLUMN booking_fee_cents_per_guest     INTEGER,
    ADD COLUMN no_show_penalty_cents_per_guest INTEGER,
    ADD COLUMN refund_window_hours             INTEGER     NOT NULL DEFAULT 48;

ALTER TABLE restaurants
    ADD CONSTRAINT chk_restaurants_guarantee_mode
        CHECK (guarantee_mode IN ('none', 'booking_fee', 'no_show')),
    ADD CONSTRAINT chk_restaurants_booking_fee
        CHECK (booking_fee_cents_per_guest IS NULL OR booking_fee_cents_per_guest > 0),
    ADD CONSTRAINT chk_restaurants_no_show_penalty
        CHECK (no_show_penalty_cents_per_guest IS NULL OR no_show_penalty_cents_per_guest > 0),
    ADD CONSTRAINT chk_restaurants_refund_window
        CHECK (refund_window_hours >= 0);

-- Un mode payant sans montant refuserait des réservations sans jamais rien encaisser.
ALTER TABLE restaurants
    ADD CONSTRAINT chk_restaurants_guarantee_amount CHECK (
        guarantee_mode = 'none'
        OR (guarantee_mode = 'booking_fee' AND booking_fee_cents_per_guest IS NOT NULL)
        OR (guarantee_mode = 'no_show' AND no_show_penalty_cents_per_guest IS NOT NULL)
    );

-- 2. Garantie portée par la réservation --------------------------------------
-- Le mode est figé à la création : changer le réglage du restaurant ne réécrit
-- jamais les réservations déjà prises.

ALTER TABLE reservations
    ADD COLUMN guarantee_mode           VARCHAR(32) NOT NULL DEFAULT 'none',
    ADD COLUMN guarantee_status         VARCHAR(32) NOT NULL DEFAULT 'not_required',
    ADD COLUMN guarantee_amount_cents   INTEGER,
    ADD COLUMN currency                 VARCHAR(3)  NOT NULL DEFAULT 'eur',
    ADD COLUMN guarantee_exempted_by    UUID,
    ADD COLUMN guarantee_expires_at     TIMESTAMPTZ,
    ADD COLUMN payment_token            VARCHAR(64),
    ADD COLUMN cancellation_token       VARCHAR(64);

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_guarantee_mode
        CHECK (guarantee_mode IN ('none', 'booking_fee', 'no_show')),
    ADD CONSTRAINT chk_reservations_guarantee_status
        CHECK (guarantee_status IN ('not_required', 'awaiting', 'secured', 'exempted',
                                    'expired', 'refunded', 'charged', 'charge_failed')),
    ADD CONSTRAINT chk_reservations_guarantee_amount
        CHECK (guarantee_amount_cents IS NULL OR guarantee_amount_cents > 0),
    ADD CONSTRAINT fk_reservations_exempted_by
        FOREIGN KEY (guarantee_exempted_by) REFERENCES users (id) ON DELETE SET NULL;

-- Les jetons sont les seules clés d'accès du convive, qui n'a pas de compte.
CREATE UNIQUE INDEX idx_reservations_payment_token
    ON reservations (payment_token) WHERE payment_token IS NOT NULL;
CREATE UNIQUE INDEX idx_reservations_cancellation_token
    ON reservations (cancellation_token) WHERE cancellation_token IS NOT NULL;

-- 3. Nouveau statut « pré-tenue » --------------------------------------------
-- 'awaiting_payment' ne tient pas dans VARCHAR(16).

ALTER TABLE reservations ALTER COLUMN status TYPE VARCHAR(32);

ALTER TABLE reservations DROP CONSTRAINT chk_reservations_status;
ALTER TABLE reservations ADD CONSTRAINT chk_reservations_status
    CHECK (status IN ('awaiting_payment', 'pending', 'confirmed', 'seated',
                      'completed', 'cancelled', 'no_show'));

-- Une réservation pré-tenue bloque la table pendant sa fenêtre de paiement :
-- c'est ce qui garantit au convive que sa table est encore là quand il paie.
ALTER TABLE reservations DROP CONSTRAINT no_overlapping_reservation;
ALTER TABLE reservations ADD CONSTRAINT no_overlapping_reservation
    EXCLUDE USING gist (
        table_id WITH =,
        tstzrange(starts_at, ends_at) WITH &&
    ) WHERE (table_id IS NOT NULL
             AND status IN ('awaiting_payment', 'pending', 'confirmed', 'seated'));

CREATE INDEX idx_reservations_awaiting_expiry
    ON reservations (guarantee_expires_at) WHERE status = 'awaiting_payment';
