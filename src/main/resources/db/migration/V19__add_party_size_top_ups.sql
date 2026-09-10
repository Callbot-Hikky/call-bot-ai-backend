-- Complément de couverts : une hausse sur une réservation payante ouvre un second
-- encaissement, avec son propre lien de paiement et son propre délai.
--
-- Jusqu'ici une hausse de couverts en mode `booking_fee` était refusée (ticket 08) :
-- les frais avaient été tarifés par couvert, et laisser la table grossir dessous
-- revenait à sous-facturer. Elle ouvre désormais une demande de complément.
--
-- Rien n'est pré-tenu : la réservation reste à N couverts, sur sa table, confirmée,
-- tant que le complément n'est pas réglé. La disponibilité constatée à la demande sera
-- revérifiée au règlement.

-- 1. Le prix par couvert, figé -------------------------------------------------
--
-- La réservation gelait jusqu'ici le montant *total* de la garantie. C'était suffisant
-- tant que le nombre de couverts ne bougeait pas ; il bouge maintenant, et ce qui est
-- réellement figé à la création est le prix *unitaire*. Le complément vaut
-- (M − N) × ce prix — jamais le tarif courant du restaurant, qu'un réglage modifié
-- après coup ne doit pas appliquer rétroactivement.

ALTER TABLE reservations
    ADD COLUMN guarantee_cents_per_guest INTEGER;

-- Reprise : le total valait exactement prix unitaire × couverts à la création, la
-- division est donc exacte. Les réservations sans garantie n'ont rien à reprendre.
UPDATE reservations
SET guarantee_cents_per_guest = guarantee_amount_cents / party_size
WHERE guarantee_amount_cents IS NOT NULL
  AND party_size IS NOT NULL
  AND party_size > 0;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_cents_per_guest
        CHECK (guarantee_cents_per_guest IS NULL OR guarantee_cents_per_guest >= 0);

-- 2. Le complément dans le registre --------------------------------------------
--
-- Un complément est un encaissement comme un autre : même table, même statut, même
-- commission Alloquence que les frais de réservation dont il prolonge le prix. Ce qu'il
-- porte en propre est son lien de paiement — un jeton neuf, distinct de celui du
-- paiement initial, à usage unique — et le nombre de couverts qu'il achète.

ALTER TABLE reservation_charges
    DROP CONSTRAINT chk_reservation_charges_kind;

ALTER TABLE reservation_charges
    ADD CONSTRAINT chk_reservation_charges_kind
        CHECK (kind IN ('booking_fee', 'no_show_penalty', 'party_size_top_up'));

ALTER TABLE reservation_charges
    ADD COLUMN payment_token     VARCHAR(64),
    ADD COLUMN token_expires_at  TIMESTAMPTZ,
    ADD COLUMN target_party_size INTEGER;

COMMENT ON COLUMN reservation_charges.target_party_size IS
    'Couverts que ce complément achète (M). La réservation reste à N jusqu''au règlement.';

-- Le jeton est la seule identification du convive : il doit désigner un encaissement
-- et un seul.
CREATE UNIQUE INDEX idx_reservation_charges_payment_token
    ON reservation_charges (payment_token) WHERE payment_token IS NOT NULL;

-- Une seule demande de complément à la fois par réservation. Deux liens vivants
-- feraient deux paiements pour une même table, dont un à rendre à la main.
CREATE UNIQUE INDEX idx_reservation_charges_one_pending_top_up
    ON reservation_charges (reservation_id)
    WHERE kind = 'party_size_top_up' AND status = 'pending';

-- Le balayage d'expiration du ticket 10 lit cet index.
CREATE INDEX idx_reservation_charges_top_up_expiry
    ON reservation_charges (token_expires_at)
    WHERE kind = 'party_size_top_up' AND status = 'pending';
