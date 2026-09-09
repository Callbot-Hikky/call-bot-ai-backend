-- Registre d'encaissements : une réservation porte zéro, un ou plusieurs encaissements.
--
-- Jusqu'ici une réservation ne pouvait porter qu'un seul mouvement d'argent : le
-- montant, la commission, l'identifiant Stripe, le statut et le reversement étaient des
-- colonnes singulières de la réservation. Un complément de couverts est un second
-- paiement, indépendant du premier : il n'entre pas dans ce modèle.
--
-- Rien ne change pour l'utilisateur. C'est la même mécanique, exprimée une fois pour
-- toutes de façon à en accepter plusieurs. Le reversement groupe désormais des
-- encaissements et non plus des réservations : un reversement peut donc contenir deux
-- lignes venant de la même réservation.

-- 1. Le registre ---------------------------------------------------------------

CREATE TABLE reservation_charges (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id           UUID        NOT NULL REFERENCES reservations (id) ON DELETE CASCADE,
    kind                     VARCHAR(32) NOT NULL,
    status                   VARCHAR(32) NOT NULL DEFAULT 'pending',
    amount_cents             INTEGER     NOT NULL,
    application_fee_cents    INTEGER     NOT NULL DEFAULT 0,
    currency                 VARCHAR(3)  NOT NULL DEFAULT 'eur',
    stripe_session_id        VARCHAR(255),
    stripe_payment_intent_id VARCHAR(255),
    paid_at                  TIMESTAMPTZ,
    refunded_at              TIMESTAMPTZ,
    refunded_amount_cents    INTEGER,
    payout_eligible_at       TIMESTAMPTZ,
    paid_out_at              TIMESTAMPTZ,
    payout_id                UUID REFERENCES payouts (id) ON DELETE SET NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Les frais de réservation portent la commission d'Alloquence ; la pénalité no-show
    -- n'en porte aucune (décision 29), d'où une commission à zéro plutôt qu'absente.
    CONSTRAINT chk_reservation_charges_kind
        CHECK (kind IN ('booking_fee', 'no_show_penalty')),
    CONSTRAINT chk_reservation_charges_status
        CHECK (status IN ('pending', 'paid', 'refunded')),
    -- Zéro toléré, et zéro seulement : la reprise ci-dessous conserve la trace Stripe
    -- d'un mouvement dont le montant manquerait plutôt que d'en inventer un.
    CONSTRAINT chk_reservation_charges_amount CHECK (amount_cents >= 0),
    CONSTRAINT chk_reservation_charges_fee CHECK (application_fee_cents >= 0),
    CONSTRAINT chk_reservation_charges_refunded_amount
        CHECK (refunded_amount_cents IS NULL OR refunded_amount_cents > 0)
);

CREATE INDEX idx_reservation_charges_reservation
    ON reservation_charges (reservation_id);

CREATE UNIQUE INDEX idx_reservation_charges_session
    ON reservation_charges (stripe_session_id) WHERE stripe_session_id IS NOT NULL;

-- Retrouver la réservation contestée : le litige ne connaît que l'intention de paiement.
CREATE INDEX idx_reservation_charges_payment_intent
    ON reservation_charges (stripe_payment_intent_id) WHERE stripe_payment_intent_id IS NOT NULL;

-- Les encaissements réglés mais pas encore reversés. Le statut 'paid' porte à lui seul
-- ce que trois colonnes disaient avant : encaissé, non remboursé.
CREATE INDEX idx_reservation_charges_payout_due
    ON reservation_charges (payout_eligible_at)
    WHERE status = 'paid' AND paid_out_at IS NULL;

CREATE INDEX idx_reservation_charges_payout
    ON reservation_charges (payout_id) WHERE payout_id IS NOT NULL;

-- 2. Reprise de l'existant -----------------------------------------------------
-- Les deux modes de garantie s'excluent, et une réservation ne pouvait porter qu'un
-- mouvement : ou une pénalité no-show, ou des frais de réservation, jamais les deux.
-- La reprise se fait donc en deux passes disjointes.

-- Les pénalités : elles occupaient paid_at, payout_eligible_at, paid_out_at et payout_id
-- au même titre que des frais, mais avec leur propre intention de paiement (V16).
INSERT INTO reservation_charges (
    reservation_id, kind, status, amount_cents, application_fee_cents, currency,
    stripe_payment_intent_id, paid_at, payout_eligible_at, paid_out_at, payout_id,
    created_at, updated_at)
SELECT r.id,
       'no_show_penalty',
       'paid',
       COALESCE(r.guarantee_amount_cents, 0),
       0,
       r.currency,
       r.stripe_penalty_intent_id,
       COALESCE(r.penalty_charged_at, r.paid_at),
       r.payout_eligible_at,
       r.paid_out_at,
       r.payout_id,
       COALESCE(r.penalty_charged_at, r.created_at),
       r.updated_at
FROM reservations r
WHERE r.penalty_charged_at IS NOT NULL
   OR r.stripe_penalty_intent_id IS NOT NULL;

-- Les frais de réservation, y compris ceux dont le paiement n'était qu'ouvert (une
-- session Stripe et une commission calculée, mais rien d'encaissé) et ceux déjà
-- remboursés ou déjà reversés.
INSERT INTO reservation_charges (
    reservation_id, kind, status, amount_cents, application_fee_cents, currency,
    stripe_session_id, stripe_payment_intent_id, paid_at, refunded_at,
    refunded_amount_cents, payout_eligible_at, paid_out_at, payout_id,
    created_at, updated_at)
SELECT r.id,
       'booking_fee',
       CASE
           WHEN r.refunded_at IS NOT NULL THEN 'refunded'
           WHEN r.paid_at IS NOT NULL THEN 'paid'
           ELSE 'pending'
       END,
       COALESCE(r.guarantee_amount_cents, 0),
       COALESCE(r.application_fee_cents, 0),
       r.currency,
       r.stripe_session_id,
       r.stripe_payment_intent_id,
       r.paid_at,
       r.refunded_at,
       r.refunded_amount_cents,
       r.payout_eligible_at,
       r.paid_out_at,
       r.payout_id,
       COALESCE(r.paid_at, r.created_at),
       r.updated_at
FROM reservations r
WHERE r.penalty_charged_at IS NULL
  AND r.stripe_penalty_intent_id IS NULL
  AND (r.stripe_session_id IS NOT NULL
       OR r.stripe_payment_intent_id IS NOT NULL
       OR r.paid_at IS NOT NULL);

-- 3. Les colonnes singulières disparaissent ------------------------------------
-- Postgres emporte avec elles les index et les contraintes CHECK qui ne portent que
-- sur elles : idx_reservations_stripe_session, idx_reservations_payment_intent,
-- idx_reservations_penalty_intent, idx_reservations_payout_due,
-- chk_reservations_application_fee et chk_reservations_refunded_amount.
--
-- Ce qui reste sur la réservation n'est pas de l'argent encaissé : guarantee_amount_cents
-- est le montant convenu et figé, penalty_due_at et penalty_attempts sont l'état de la
-- relance, et les colonnes de carte servent à débiter, pas à tracer un débit.

ALTER TABLE reservations
    DROP COLUMN stripe_session_id,
    DROP COLUMN stripe_payment_intent_id,
    DROP COLUMN application_fee_cents,
    DROP COLUMN paid_at,
    DROP COLUMN refunded_at,
    DROP COLUMN refunded_amount_cents,
    DROP COLUMN payout_eligible_at,
    DROP COLUMN paid_out_at,
    DROP COLUMN payout_id,
    DROP COLUMN penalty_charged_at,
    DROP COLUMN stripe_penalty_intent_id;
