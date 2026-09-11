package com.callbot.ai.gateway;

/**
 * A provider webhook, normalised to the few fields the domain acts on.
 *
 * @param type                   what happened
 * @param checkoutSessionId      the checkout session this event refers to (matches the
 *                               pending subscription row)
 * @param providerSubscriptionId the subscription created on the provider side, if any
 */
public record PaymentEvent(PaymentEventType type, String checkoutSessionId, String providerSubscriptionId) {
}
