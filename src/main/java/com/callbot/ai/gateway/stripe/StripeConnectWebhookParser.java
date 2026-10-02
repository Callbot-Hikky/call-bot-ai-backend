package com.callbot.ai.gateway.stripe;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.callbot.ai.exception.InvalidPaymentSignatureException;
import com.callbot.ai.exception.PaymentGatewayException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Account;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;

import lombok.RequiredArgsConstructor;

/**
 * Verifies and translates Stripe Connect webhooks.
 *
 * <p>A sibling of {@link StripeWebhookParser} rather than an extension of it: Stripe
 * signs every endpoint with its own secret, so the two cannot share verification, and
 * the events they care about have nothing in common.
 */
@Component
@RequiredArgsConstructor
public class StripeConnectWebhookParser {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectWebhookParser.class);

    private static final String SIGNATURE_HEADER = "stripe-signature";
    private static final String CHECKOUT_COMPLETED = "checkout.session.completed";
    private static final String ACCOUNT_UPDATED = "account.updated";
    private static final String DISPUTE_CREATED = "charge.dispute.created";
    private static final String SETUP_MODE = "setup";

    private final StripeConnectProperties properties;
    private final StripeProperties platformProperties;

    /** Events from connected accounts, received on {@code /api/payments/webhook}. */
    public Optional<ConnectWebhookEvent> parse(String payload, Map<String, String> headers) {
        if (properties.webhookSecret() == null || properties.webhookSecret().isBlank()) {
            throw new PaymentGatewayException(
                    "Stripe Connect webhook secret is not configured (STRIPE_CONNECT_WEBHOOK_SECRET)");
        }

        Event event = verify(payload, headers, properties.webhookSecret());

        return switch (event.getType()) {
            case CHECKOUT_COMPLETED -> checkoutCompleted(event);
            case ACCOUNT_UPDATED -> accountUpdated(event);
            case DISPUTE_CREATED -> disputeOpened(event);
            default -> Optional.empty();
        };
    }

    /**
     * Reservation events Stripe reports to the platform's own endpoint.
     *
     * <p>Booking fees are destination charges: the checkout session belongs to the
     * platform, not to the restaurant, so its completion and any dispute on it never
     * reach the Connect endpoint. They arrive on {@code /api/offers/webhook}, signed
     * with the platform secret, mixed with the subscription checkouts — which carry no
     * reservation and come back empty here.
     *
     * <p>Without a Stripe signature the body is not Stripe's to begin with; it is left
     * to whichever provider parser owns that endpoint.
     */
    public Optional<ConnectWebhookEvent> parsePlatform(String payload, Map<String, String> headers) {
        if (signatureFrom(headers) == null) {
            return Optional.empty();
        }
        if (platformProperties.webhookSecret() == null || platformProperties.webhookSecret().isBlank()) {
            throw new PaymentGatewayException("Stripe webhook secret is not configured (STRIPE_WEBHOOK_SECRET)");
        }

        Event event = verify(payload, headers, platformProperties.webhookSecret());

        return switch (event.getType()) {
            case CHECKOUT_COMPLETED -> checkoutCompleted(event);
            case DISPUTE_CREATED -> disputeOpened(event);
            default -> Optional.empty();
        };
    }

    private static Event verify(String payload, Map<String, String> headers, String secret) {
        String signature = signatureFrom(headers);
        if (signature == null) {
            throw new InvalidPaymentSignatureException("Missing Stripe-Signature header");
        }
        try {
            return Webhook.constructEvent(payload, signature, secret);
        } catch (SignatureVerificationException e) {
            throw new InvalidPaymentSignatureException("Invalid Stripe webhook signature");
        }
    }

    /**
     * A completed checkout is either a booking fee that was paid, or a card that was
     * registered without being charged. Stripe distinguishes them by the session mode.
     */
    private Optional<ConnectWebhookEvent> checkoutCompleted(Event event) {
        return event.getDataObjectDeserializer().getObject()
                .filter(Session.class::isInstance)
                .map(Session.class::cast)
                .flatMap(session -> reservationIdOf(session)
                        .map(id -> SETUP_MODE.equals(session.getMode())
                                ? new ConnectWebhookEvent.CardRegistered(
                                        id, session.getSetupIntent(), event.getAccount())
                                : new ConnectWebhookEvent.ReservationPaid(
                                        id,
                                        session.getId(),
                                        session.getPaymentIntent(),
                                        session.getAmountTotal() == null
                                                ? 0
                                                : session.getAmountTotal().intValue())));
    }

    /**
     * Sessions opened by the subscription flow reach the same endpoint and carry no
     * reservation. Dropping them here keeps the two flows from confusing each other.
     */
    private Optional<UUID> reservationIdOf(Session session) {
        Map<String, String> metadata = session.getMetadata();
        String raw = metadata == null ? null : metadata.get(StripeConnectGateway.RESERVATION_METADATA_KEY);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            log.warn("Stripe session {} carries an unreadable reservation id: {}", session.getId(), raw);
            return Optional.empty();
        }
    }

    private Optional<ConnectWebhookEvent> disputeOpened(Event event) {
        return event.getDataObjectDeserializer().getObject()
                .filter(Dispute.class::isInstance)
                .map(Dispute.class::cast)
                .filter(dispute -> dispute.getPaymentIntent() != null)
                .map(dispute -> new ConnectWebhookEvent.DisputeOpened(
                        dispute.getPaymentIntent(),
                        dispute.getAmount() == null ? 0 : dispute.getAmount().intValue()));
    }

    private Optional<ConnectWebhookEvent> accountUpdated(Event event) {
        return event.getDataObjectDeserializer().getObject()
                .filter(Account.class::isInstance)
                .map(Account.class::cast)
                .map(account -> new ConnectWebhookEvent.AccountUpdated(StripeConnectGateway.statusOf(account)));
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
