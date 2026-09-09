package com.callbot.ai.dto;

import com.callbot.ai.model.Organization;

/**
 * State of a restaurateur's payment account, as the back-office needs it to decide
 * whether the paying guarantee modes can be offered at all.
 */
public record ConnectAccountResponse(
        boolean connected,
        boolean chargesEnabled,
        boolean payoutsEnabled,
        boolean detailsSubmitted) {

    public static ConnectAccountResponse from(Organization organization) {
        return new ConnectAccountResponse(
                organization.getStripeAccountId() != null,
                organization.isStripeChargesEnabled(),
                organization.isStripePayoutsEnabled(),
                organization.isStripeDetailsSubmitted());
    }
}
