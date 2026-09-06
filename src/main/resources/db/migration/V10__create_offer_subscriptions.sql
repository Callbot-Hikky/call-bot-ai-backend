-- Offer subscriptions: a customer taking a paid plan (e.g. the 99 EUR "Pro" offer)
-- through Stripe Checkout. One row is created (status 'pending') when the checkout
-- session is opened, and later moved to 'active' once payment is confirmed.

CREATE TABLE offer_subscriptions (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    organization_id        UUID         NOT NULL,
    user_id                UUID,
    offer_code             VARCHAR(32)  NOT NULL,
    amount_cents           INTEGER      NOT NULL,
    currency               VARCHAR(3)   NOT NULL DEFAULT 'eur',
    status                 VARCHAR(16)  NOT NULL DEFAULT 'pending',
    stripe_session_id      VARCHAR(255),
    stripe_subscription_id VARCHAR(255),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    CONSTRAINT fk_offer_subscriptions_organization FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_offer_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_offer_subscriptions_amount CHECK (amount_cents > 0),
    CONSTRAINT chk_offer_subscriptions_status CHECK (status IN ('pending', 'active', 'cancelled', 'failed'))
);

CREATE INDEX idx_offer_subscriptions_organization ON offer_subscriptions (organization_id);
CREATE UNIQUE INDEX idx_offer_subscriptions_session ON offer_subscriptions (stripe_session_id);
