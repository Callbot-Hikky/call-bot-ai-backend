package com.callbot.ai.dto;

/**
 * Result of opening a checkout: the frontend redirects the browser to {@code checkoutUrl}
 * (the provider's hosted checkout page). {@code sessionId} lets the client reconcile later.
 */
public record CheckoutSessionResponse(String sessionId, String checkoutUrl) {
}
