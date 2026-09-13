-- Le paiement d'une pénalité no-show ne doit pas se confondre avec celui de frais de
-- réservation.
--
-- Les deux atterrissaient dans stripe_payment_intent_id, la colonne par laquelle un
-- litige bancaire retrouve sa réservation. Un litige sur une pénalité était donc compté
-- dans le taux de litige d'Alloquence — qui n'a pourtant pris aucune commission dessus,
-- et n'a rien à absorber.

ALTER TABLE reservations
    ADD COLUMN stripe_penalty_intent_id VARCHAR(255);

CREATE INDEX idx_reservations_penalty_intent
    ON reservations (stripe_penalty_intent_id) WHERE stripe_penalty_intent_id IS NOT NULL;
