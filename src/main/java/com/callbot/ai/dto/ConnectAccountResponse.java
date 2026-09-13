package com.callbot.ai.dto;

import com.callbot.ai.model.Restaurant;

/**
 * State of a restaurateur's payment account, as the back-office needs it to decide
 * whether the paying guarantee modes can be offered at all.
 */
public record ConnectAccountResponse(
        boolean connected,
        boolean chargesEnabled,
        boolean payoutsEnabled,
        boolean detailsSubmitted,
        int disputeCount,
        long paidReservationCount) {

    public static ConnectAccountResponse from(Restaurant restaurant, long paidReservationCount) {
        return new ConnectAccountResponse(
                restaurant.getStripeAccountId() != null,
                restaurant.isStripeChargesEnabled(),
                restaurant.isStripePayoutsEnabled(),
                restaurant.isStripeDetailsSubmitted(),
                restaurant.getStripeDisputeCount(),
                paidReservationCount);
    }
}
