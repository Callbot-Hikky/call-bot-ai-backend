package com.callbot.ai.gateway.stripe;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.callbot.ai.exception.InvalidPaymentSignatureException;
import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.PaymentEvent;
import com.callbot.ai.gateway.PaymentEventParser;
import com.callbot.ai.gateway.PaymentEventType;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

import lombok.RequiredArgsConstructor;

/**
 * Turns a raw Stripe webhook into a {@link PaymentEvent}. All Stripe specifics —
 * the {@code Stripe-Signature} header, the event names, the SDK deserialisation —
 * stop here.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = StripeCheckoutGateway.PROVIDER_CODE,
        matchIfMissing = true)
@RequiredArgsConstructor
public class StripeWebhookParser implements PaymentEventParser {

    private static final String SIGNATURE_HEADER = "stripe-signature";
    private static final String CHECKOUT_COMPLETED = "checkout.session.completed";

    private final StripeProperties properties;

    @Override
    public Optional<PaymentEvent> parse(String payload, Map<String, String> headers) {
        if (properties.webhookSecret() == null || properties.webhookSecret().isBlank()) {
            throw new PaymentGatewayException("Stripe webhook secret is not configured (STRIPE_WEBHOOK_SECRET)");
        }

        String signature = signatureFrom(headers);
        if (signature == null) {
            throw new InvalidPaymentSignatureException("Missing Stripe-Signature header");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, properties.webhookSecret());
        } catch (SignatureVerificationException e) {
            throw new InvalidPaymentSignatureException("Invalid Stripe webhook signature");
        }

        if (!CHECKOUT_COMPLETED.equals(event.getType())) {
            // Stripe sends many event types; anything we do not act on is acknowledged and dropped.
            return Optional.empty();
        }

        return event.getDataObjectDeserializer().getObject()
                .filter(Session.class::isInstance)
                .map(Session.class::cast)
                .map(session -> new PaymentEvent(
                        PaymentEventType.CHECKOUT_COMPLETED,
                        session.getId(),
                        session.getSubscription()));
    }

    /** Header lookup is case-insensitive: HTTP header casing is not guaranteed. */
    private static String signatureFrom(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }

        return headers.entrySet().stream()
                .filter(entry -> SIGNATURE_HEADER.equals(entry.getKey().toLowerCase(Locale.ROOT)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
