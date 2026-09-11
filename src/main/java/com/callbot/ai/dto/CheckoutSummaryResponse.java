package com.callbot.ai.dto;

import com.callbot.ai.model.OfferSubscription;

/**
 * Read-only recap shown on the success page after checkout. Built from our own
 * subscription record (no round-trip to the payment provider needed).
 */
public record CheckoutSummaryResponse(
        String offerCode,
        int amountCents,
        String currency,
        String status) {

    public static CheckoutSummaryResponse from(OfferSubscription subscription) {
        return new CheckoutSummaryResponse(
                subscription.getOfferCode(),
                subscription.getAmountCents(),
                subscription.getCurrency(),
                subscription.getStatus());
    }
}
