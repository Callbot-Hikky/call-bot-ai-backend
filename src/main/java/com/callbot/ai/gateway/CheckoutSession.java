package com.callbot.ai.gateway;

/**
 * Provider-agnostic view of a hosted checkout session: the id we reconcile on, and the
 * url the browser is redirected to.
 */
public record CheckoutSession(String id, String url) {
}
