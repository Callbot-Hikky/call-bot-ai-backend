-- Remove the payment provider's name from the domain schema: the columns describe a
-- checkout, not a Stripe checkout. A `provider` column records which gateway created the
-- row, so subscriptions taken before a provider switch stay attributable.

ALTER TABLE offer_subscriptions RENAME COLUMN stripe_session_id TO checkout_session_id;
ALTER TABLE offer_subscriptions RENAME COLUMN stripe_subscription_id TO provider_subscription_id;

ALTER TABLE offer_subscriptions
    ADD COLUMN provider VARCHAR(32) NOT NULL DEFAULT 'stripe';

ALTER INDEX idx_offer_subscriptions_session RENAME TO idx_offer_subscriptions_checkout_session;
