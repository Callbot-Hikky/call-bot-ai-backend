package com.callbot.ai.gateway.stripe;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stripe adapter configuration. Lives with the adapter, not in {@code config}, so the
 * rest of the application never has a reason to import it.
 *
 * <p>{@code secretKey} must be provided (STRIPE_SECRET_KEY) for checkout creation to
 * work. {@code successUrl} / {@code cancelUrl} are the frontend pages the customer is
 * redirected back to after (or instead of) paying.
 *
 * <p>{@code priceIds} maps an offer code to the recurring Stripe Price to bill (e.g.
 * {@code pro -> price_123}). An offer with no entry falls back to an inline price, so the
 * flow keeps working without pre-created prices. Adding a plan is a config change here,
 * not a code change in the adapter.
 *
 * <p>{@code webhookSecret} (whsec_...) verifies the signature of incoming Stripe webhooks.
 */
@ConfigurationProperties(prefix = "app.stripe")
public record StripeProperties(
        String secretKey,
        String successUrl,
        String cancelUrl,
        Map<String, String> priceIds,
        String webhookSecret) {

    public StripeProperties {
        priceIds = priceIds == null ? Map.of() : priceIds;
    }

    /** The configured Price for an offer code, or {@code null} when none is set. */
    public String priceIdFor(String offerCode) {
        String priceId = priceIds.get(offerCode);

        return priceId == null || priceId.isBlank() ? null : priceId;
    }
}
