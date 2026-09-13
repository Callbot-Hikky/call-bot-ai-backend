package com.callbot.ai.gateway.stripe;

/**
 * The card a diner registered: the customer it hangs off, and the method itself.
 *
 * <p>Both live on the restaurant's connected account, so both are needed together to
 * debit later — hence one type rather than two loose strings.
 */
public record RegisteredCard(String customerId, String paymentMethodId) {
}
