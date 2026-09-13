-- Suivi du taux de litige par organisation.
--
-- Les litiges bancaires sont absorbés par Alloquence, mais un restaurateur dont les
-- clients contestent systématiquement leurs frais est un risque : il faut pouvoir le
-- voir venir plutôt que le découvrir sur un relevé.

ALTER TABLE organizations
    ADD COLUMN stripe_dispute_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN stripe_last_dispute_at TIMESTAMPTZ;

ALTER TABLE organizations
    ADD CONSTRAINT chk_organizations_dispute_count CHECK (stripe_dispute_count >= 0);

-- Retrouver la réservation contestée : le litige ne connaît que l'intention de paiement.
CREATE INDEX idx_reservations_payment_intent
    ON reservations (stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;
