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

    /**
     * A diner registered a card for a no-show guarantee. Nothing was charged.
     *
     * <p>Carries the connected account because the setup session was created there, and
     * the card can only be read back — and later debited — from that same account.
     */
    record CardRegistered(
            UUID reservationId,
            String setupIntentId,
            String connectedAccountId) implements ConnectWebhookEvent {
    }

    /**
     * A diner disputed their booking fee with their bank.
     *
     * <p>Carries the payment intent rather than a reservation id: a dispute object has no
     * metadata of ours, so the reservation is found from what was charged.
     */
    record DisputeOpened(String paymentIntentId, int amountCents) implements ConnectWebhookEvent {
    }
}
