package com.callbot.ai.gateway.stripe;

import java.util.UUID;

/** What Stripe needs to debit a no-show penalty from a card registered earlier. */
public record NoShowCharge(
        UUID reservationId,
        int amountCents,
        String currency,
        String customerId,
        String paymentMethodId,
        String connectedAccountId,
        String idempotencyKey) {
}
