-- Encaissement des réservations : compte connecté Stripe par organisation,
-- trace du paiement sur chaque réservation, et registre des reversements.

-- 1. Compte connecté, au niveau de l'organisation ------------------------------
-- Voir docs/adr/0001 : destination charges, l'argent ne transite pas par un
-- compte de plateforme classique.

ALTER TABLE organizations
    ADD COLUMN stripe_account_id        VARCHAR(255),
    ADD COLUMN stripe_charges_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_payouts_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_details_submitted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_onboarded_at      TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_organizations_stripe_account
    ON organizations (stripe_account_id) WHERE stripe_account_id IS NOT NULL;

-- 2. Registre des reversements ------------------------------------------------
-- Les comptes connectés sont créés en versement manuel : l'argent d'une
-- destination charge arrive sur leur solde immédiatement, mais ne part vers la
-- banque du restaurateur que lorsque Alloquence le décide — J+1 après le service.

CREATE TABLE payouts (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    stripe_payout_id  VARCHAR(255),
    amount_cents      INTEGER     NOT NULL,
    currency          VARCHAR(3)  NOT NULL DEFAULT 'eur',
    reservation_count INTEGER     NOT NULL DEFAULT 0,
    status            VARCHAR(32) NOT NULL DEFAULT 'pending',
    failure_message   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_payouts_amount CHECK (amount_cents > 0),
    CONSTRAINT chk_payouts_status CHECK (status IN ('pending', 'paid', 'failed'))
);

CREATE INDEX idx_payouts_organization ON payouts (organization_id, created_at DESC);

-- 3. Trace du paiement sur la réservation -------------------------------------

ALTER TABLE reservations
    ADD COLUMN stripe_session_id          VARCHAR(255),
    ADD COLUMN stripe_payment_intent_id   VARCHAR(255),
    ADD COLUMN application_fee_cents      INTEGER,
    ADD COLUMN paid_at                    TIMESTAMPTZ,
    ADD COLUMN refunded_at                TIMESTAMPTZ,
    ADD COLUMN refunded_amount_cents      INTEGER,
    ADD COLUMN payout_eligible_at         TIMESTAMPTZ,
    ADD COLUMN paid_out_at                TIMESTAMPTZ,
    ADD COLUMN payout_id                  UUID REFERENCES payouts (id) ON DELETE SET NULL;

-- La fenêtre de remboursement fait partie de ce qui a été annoncé au convive au
-- moment de la réservation. Comme le mode et le montant, elle se fige : le
-- restaurateur qui la raccourcit ne peut pas revenir sur une promesse déjà faite.
ALTER TABLE reservations
    ADD COLUMN guarantee_refund_window_hours INTEGER;

ALTER TABLE reservations
    ADD CONSTRAINT chk_reservations_application_fee
        CHECK (application_fee_cents IS NULL OR application_fee_cents >= 0),
    ADD CONSTRAINT chk_reservations_refunded_amount
        CHECK (refunded_amount_cents IS NULL OR refunded_amount_cents > 0),
    ADD CONSTRAINT chk_reservations_refund_window
        CHECK (guarantee_refund_window_hours IS NULL OR guarantee_refund_window_hours >= 0);

CREATE UNIQUE INDEX idx_reservations_stripe_session
    ON reservations (stripe_session_id) WHERE stripe_session_id IS NOT NULL;

-- Les réservations dont l'argent est encaissé mais pas encore reversé.
CREATE INDEX idx_reservations_payout_due
    ON reservations (payout_eligible_at)
    WHERE paid_at IS NOT NULL AND paid_out_at IS NULL AND refunded_at IS NULL;
