package com.callbot.ai.gateway.stripe;

import java.util.UUID;

/**
 * Everything Stripe needs to collect one booking fee on behalf of a restaurant.
 *
 * <p>These fields always travel together — the amount is meaningless without the
 * account it goes to and the commission held back — so they are one type rather than
 * seven parameters.
 */
public record BookingFeeCharge(
        UUID reservationId,
        String restaurantName,
        int amountCents,
        String currency,
        int applicationFeeCents,
        String connectedAccountId,
        String customerEmail) {
}
