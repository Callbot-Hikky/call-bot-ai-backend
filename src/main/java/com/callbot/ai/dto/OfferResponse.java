package com.callbot.ai.dto;

import com.callbot.ai.model.OfferPlan;

/**
 * A plan the client can subscribe to. {@code amountCents} keeps money as an integer
 * (99.00 EUR -> 9900) to avoid floating-point rounding.
 */
public record OfferResponse(
        String code,
        String label,
        int amountCents,
        String currency,
        String interval) {

    public static OfferResponse from(OfferPlan plan) {
        return new OfferResponse(
                plan.getCode(),
                plan.getLabel(),
                plan.getAmountCents(),
                plan.getCurrency(),
                plan.getInterval());
    }
}
