package com.callbot.ai.gateway.stripe;

/**
 * What Stripe currently allows a connected account to do.
 *
 * <p>{@code chargesEnabled} is the one that gates a paying guarantee mode: an account
 * that cannot take charges would send diners to a payment page that fails.
 */
public record ConnectAccountStatus(
        String accountId,
        boolean chargesEnabled,
        boolean payoutsEnabled,
        boolean detailsSubmitted) {
}
