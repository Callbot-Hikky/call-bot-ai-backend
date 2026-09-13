-- Garantie no-show : la carte est enregistrée sans être débitée, et n'est débitée
-- qu'après un constat d'absence posé par le personnel.

ALTER TABLE reservations
    -- Moyen de paiement enregistré sur le compte connecté du restaurateur, jamais chez
    -- Alloquence : c'est lui qui débitera, et lui qui encaissera.
    ADD COLUMN stripe_customer_id        VARCHAR(255),
    ADD COLUMN stripe_payment_method_id  VARCHAR(255),
    ADD COLUMN stripe_setup_intent_id    VARCHAR(255),
    ADD COLUMN payment_method_detached_at TIMESTAMPTZ,

    -- Constat d'absence. Toujours humain : le silence du système ne vaut jamais absence.
    ADD COLUMN no_show_recorded_at       TIMESTAMPTZ,
    ADD COLUMN no_show_recorded_by       UUID REFERENCES users (id) ON DELETE SET NULL,

    -- Fenêtre d'annulation : le débit n'est tenté qu'à cette heure, pour laisser au
    -- personnel le temps de revenir sur un constat posé par erreur.
    ADD COLUMN penalty_due_at            TIMESTAMPTZ,
    ADD COLUMN penalty_attempts          INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN penalty_charged_at        TIMESTAMPTZ;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_penalty_attempts CHECK (penalty_attempts >= 0),
    -- Un constat sans auteur serait un constat que personne n'assume.
    ADD CONSTRAINT chk_reservations_no_show_author
        CHECK ((no_show_recorded_at IS NULL) = (no_show_recorded_by IS NULL));

-- Les débits à tenter : constat posé, fenêtre écoulée, rien encore encaissé.
CREATE INDEX idx_reservations_penalty_due
    ON reservations (penalty_due_at)
    WHERE penalty_due_at IS NOT NULL AND penalty_charged_at IS NULL;

-- Les moyens de paiement à détacher une fois le service passé.
CREATE INDEX idx_reservations_payment_method_to_detach
    ON reservations (ends_at)
    WHERE stripe_payment_method_id IS NOT NULL AND payment_method_detached_at IS NULL;
