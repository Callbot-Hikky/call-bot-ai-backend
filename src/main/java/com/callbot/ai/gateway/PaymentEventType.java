package com.callbot.ai.gateway;

/**
 * The provider notifications the application reacts to. Adapters translate their own
 * event names (Stripe's {@code checkout.session.completed}, ...) into these, and drop
 * everything else.
 */
public enum PaymentEventType {

    /** The customer completed payment: the pending subscription can be activated. */
    CHECKOUT_COMPLETED
}
