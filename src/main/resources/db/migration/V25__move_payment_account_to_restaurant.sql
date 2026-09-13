-- Le compte de paiement descend de l'organisation au restaurant.
--
-- Un compte Stripe est lié à une entité légale et à un IBAN. Deux restaurants d'un même
-- propriétaire sont souvent deux sociétés avec deux banques : partager un compte
-- enverrait l'argent du second sur celle du premier.
--
-- Le mode de garantie était déjà réglé par restaurant ; le compte qui le rend possible
-- vit désormais au même endroit.

ALTER TABLE restaurants
    ADD COLUMN stripe_account_id        VARCHAR(255),
    ADD COLUMN stripe_charges_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_payouts_enabled   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_details_submitted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN stripe_onboarded_at      TIMESTAMPTZ,
    ADD COLUMN stripe_dispute_count     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN stripe_last_dispute_at   TIMESTAMPTZ;

ALTER TABLE restaurants
    ADD CONSTRAINT chk_restaurants_dispute_count CHECK (stripe_dispute_count >= 0);

CREATE UNIQUE INDEX idx_restaurants_stripe_account
    ON restaurants (stripe_account_id) WHERE stripe_account_id IS NOT NULL;

-- Reprise des comptes existants. Un compte ne peut appartenir qu'à un restaurant :
-- il n'est repris que là où l'organisation n'en exploite qu'un seul, faute de quoi on
-- attribuerait arbitrairement l'IBAN d'un établissement à un autre. Les organisations
-- qui en exploitent plusieurs refont l'inscription par restaurant.
UPDATE restaurants r
SET stripe_account_id        = o.stripe_account_id,
    stripe_charges_enabled   = o.stripe_charges_enabled,
    stripe_payouts_enabled   = o.stripe_payouts_enabled,
    stripe_details_submitted = o.stripe_details_submitted,
    stripe_onboarded_at      = o.stripe_onboarded_at,
    stripe_dispute_count     = o.stripe_dispute_count,
    stripe_last_dispute_at   = o.stripe_last_dispute_at
FROM organizations o
WHERE r.organization_id = o.id
  AND o.stripe_account_id IS NOT NULL
  AND (SELECT count(*) FROM restaurants r2 WHERE r2.organization_id = o.id) = 1;

ALTER TABLE organizations
    DROP COLUMN stripe_account_id,
    DROP COLUMN stripe_charges_enabled,
    DROP COLUMN stripe_payouts_enabled,
    DROP COLUMN stripe_details_submitted,
    DROP COLUMN stripe_onboarded_at,
    DROP COLUMN stripe_dispute_count,
    DROP COLUMN stripe_last_dispute_at;

-- Les reversements suivent : chaque restaurant est versé sur sa propre banque.
ALTER TABLE payouts ADD COLUMN restaurant_id UUID REFERENCES restaurants (id) ON DELETE CASCADE;

UPDATE payouts p
SET restaurant_id = (
    SELECT r.id FROM restaurants r
    WHERE r.organization_id = p.organization_id
    ORDER BY r.created_at
    LIMIT 1);

DELETE FROM payouts WHERE restaurant_id IS NULL;

ALTER TABLE payouts
    ALTER COLUMN restaurant_id SET NOT NULL,
    DROP COLUMN organization_id;

DROP INDEX IF EXISTS idx_payouts_organization;
CREATE INDEX idx_payouts_restaurant ON payouts (restaurant_id, created_at DESC);
