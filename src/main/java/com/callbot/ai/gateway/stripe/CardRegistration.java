package com.callbot.ai.gateway.stripe;

import java.util.UUID;

/** What Stripe needs to save a diner's card for a no-show guarantee, without charging it. */
public record CardRegistration(
        UUID reservationId,
        String restaurantName,
        String currency,
        String connectedAccountId,
        String customerEmail) {
}
