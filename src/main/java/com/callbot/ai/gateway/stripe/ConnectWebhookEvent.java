package com.callbot.ai.gateway.stripe;

import java.util.UUID;

/**
 * A Stripe Connect notification the application acts on. Anything else Stripe sends is
 * acknowledged and dropped by the parser, so this type only ever holds real work.
 */
public sealed interface ConnectWebhookEvent {

    /** A diner completed the hosted payment for their booking fee. */
    record ReservationPaid(
            UUID reservationId,
            String sessionId,
            String paymentIntentId,
            int amountTotalCents) implements ConnectWebhookEvent {
    }

    /**
     * A connected account changed — typically onboarding finishing, which is what
     * unlocks a paying guarantee mode for that organization.
     */
    record AccountUpdated(ConnectAccountStatus status) implements ConnectWebhookEvent {
    }
}
