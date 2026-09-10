package com.callbot.ai.gateway.stripe;

import java.util.UUID;

/**
 * Everything Stripe needs to collect the difference owed when a party grows.
 *
 * <p>Separate from {@link BookingFeeCharge} despite the same shape: the diner sees a
 * different line on the hosted page — how many guests they are adding, not a fee for
 * the table — and a record that says "top-up" cannot be handed to the fee call by
 * mistake.
 */
public record PartySizeTopUpCharge(
        UUID reservationId,
        String restaurantName,
        int extraGuests,
        int amountCents,
        String currency,
        int applicationFeeCents,
        String connectedAccountId,
        String customerEmail) {
}
