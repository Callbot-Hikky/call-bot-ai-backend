package com.callbot.ai.gateway.stripe;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the diner-payment flow, which runs on Stripe Connect.
 *
 * <p>Separate from {@link StripeProperties} because it configures a different thing:
 * subscriptions bill restaurateurs on Alloquence's own account, whereas this money
 * belongs to the restaurateur and only passes through. In particular the webhook
 * secret is a different one — Stripe signs each endpoint with its own key, and reusing
 * the subscription secret here would silently reject every event.
 *
 * <p>{@code returnUrl} / {@code refreshUrl} are the back-office pages Stripe sends the
 * restaurateur back to at the end (or on expiry) of onboarding.
 *
 * <p>{@code topUpSuccessUrl} is a page of its own because paying a top-up does not mean
 * what paying a booking fee means. A booking fee bought a table; a top-up bought the
 * right to be moved to a larger one <em>if</em> there is one, and the ordinary success
 * page would tell the diner their reservation is being confirmed when it may be about
 * to be refunded instead.
 */
@ConfigurationProperties(prefix = "app.stripe-connect")
public record StripeConnectProperties(
        String webhookSecret,
        String returnUrl,
        String refreshUrl,
        String successUrl,
        String topUpSuccessUrl,
        String cancelUrl,
        String country) {

    public StripeConnectProperties {
        country = (country == null || country.isBlank()) ? "FR" : country;
    }
}
