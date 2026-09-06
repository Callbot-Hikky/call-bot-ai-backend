package com.callbot.ai.gateway;

import com.callbot.ai.model.OfferPlan;

/**
 * Outbound port to the payment provider. The domain talks to this interface only, so
 * swapping Stripe for another provider (Paddle, Mollie, Lemon Squeezy...) means adding
 * an adapter in a sub-package and pointing {@code app.payment.provider} at it — no
 * change to {@code OfferService}, the controllers or the entities.
 *
 * <p>Implementations must not leak provider SDK types through this interface.
 */
public interface PaymentGateway {

    /**
     * Identifier of the provider behind this implementation (e.g. {@code "stripe"}).
     * Persisted alongside each subscription so rows created before a provider switch
     * stay attributable.
     */
    String providerCode();

    /**
     * Opens a hosted checkout session for a recurring subscription to the given plan.
     *
     * @param plan          the plan being purchased (defines amount / currency / interval)
     * @param customerEmail the authenticated customer's email, pre-filled on the hosted page
     * @return the created session (id + redirect url)
     * @throws com.callbot.ai.exception.PaymentGatewayException if the provider is
     *         misconfigured or rejects the request
     */
    CheckoutSession createSubscriptionCheckout(OfferPlan plan, String customerEmail);
}
