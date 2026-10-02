package com.callbot.ai.gateway.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.callbot.ai.exception.InvalidPaymentSignatureException;
import com.stripe.Stripe;

/**
 * Booking fees are destination charges: the checkout lives on the platform account, so
 * Stripe reports them to the platform's own endpoint, signed with the platform secret.
 * These tests pin that path down next to the connected-account one.
 */
class StripeConnectWebhookParserTest {

    private static final String CONNECT_SECRET = "whsec_connect";
    private static final String PLATFORM_SECRET = "whsec_platform";
    private static final UUID RESERVATION_ID = UUID.fromString("7b8f3a52-4a1e-4c2d-9a59-1f5c0f6e2b11");

    private final StripeConnectWebhookParser parser = new StripeConnectWebhookParser(
            new StripeConnectProperties(CONNECT_SECRET, "", "", "", "", "", "FR"),
            new StripeProperties("sk_test", "", "", Map.of(), PLATFORM_SECRET));

    @Test
    void parsePlatform_paidReservationCheckout_returnsReservationPaid() {
        String payload = paymentCheckoutPayload(Map.of("reservationId", RESERVATION_ID.toString()));

        Optional<ConnectWebhookEvent> event = parser.parsePlatform(payload, signedWith(PLATFORM_SECRET, payload));

        assertThat(event).contains(new ConnectWebhookEvent.ReservationPaid(
                RESERVATION_ID, "cs_test_1", "pi_test_1", 2000));
    }

    @Test
    void parsePlatform_subscriptionCheckout_isLeftToTheOfferFlow() {
        String payload = paymentCheckoutPayload(Map.of());

        assertThat(parser.parsePlatform(payload, signedWith(PLATFORM_SECRET, payload))).isEmpty();
    }

    @Test
    void parsePlatform_disputeOnBookingFee_returnsDisputeOpened() {
        String payload = """
                {"id":"evt_2","object":"event","type":"charge.dispute.created","api_version":"%s",\
                "data":{"object":{"id":"dp_1","object":"dispute","payment_intent":"pi_test_1","amount":2000}}}"""
                .formatted(Stripe.API_VERSION);

        assertThat(parser.parsePlatform(payload, signedWith(PLATFORM_SECRET, payload)))
                .contains(new ConnectWebhookEvent.DisputeOpened("pi_test_1", 2000));
    }

    @Test
    void parsePlatform_accountUpdated_isIgnored() {
        String payload = """
                {"id":"evt_3","object":"event","type":"account.updated","api_version":"%s",\
                "data":{"object":{"id":"acct_1","object":"account"}}}""".formatted(Stripe.API_VERSION);

        assertThat(parser.parsePlatform(payload, signedWith(PLATFORM_SECRET, payload))).isEmpty();
    }

    @Test
    void parsePlatform_signedWithConnectSecret_isRejected() {
        String payload = paymentCheckoutPayload(Map.of("reservationId", RESERVATION_ID.toString()));

        assertThatThrownBy(() -> parser.parsePlatform(payload, signedWith(CONNECT_SECRET, payload)))
                .isInstanceOf(InvalidPaymentSignatureException.class);
    }

    @Test
    void parse_connectEndpointStillVerifiesWithConnectSecret() {
        String payload = paymentCheckoutPayload(Map.of("reservationId", RESERVATION_ID.toString()));

        assertThat(parser.parse(payload, signedWith(CONNECT_SECRET, payload))).isPresent();
    }

    private static String paymentCheckoutPayload(Map<String, String> metadata) {
        String metadataJson = metadata.entrySet().stream()
                .map(entry -> "\"%s\":\"%s\"".formatted(entry.getKey(), entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("");

        return """
                {"id":"evt_1","object":"event","type":"checkout.session.completed","api_version":"%s",\
                "data":{"object":{"id":"cs_test_1","object":"checkout.session","mode":"payment",\
                "payment_intent":"pi_test_1","amount_total":2000,"metadata":{%s}}}}"""
                .formatted(Stripe.API_VERSION, metadataJson);
    }

    /** Rebuilds the {@code Stripe-Signature} header the way Stripe computes it. */
    private static Map<String, String> signedWith(String secret, String payload) {
        long timestamp = Instant.now().getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append("%02x".formatted(b));
            }

            return Map.of("Stripe-Signature", "t=" + timestamp + ",v1=" + hex);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
