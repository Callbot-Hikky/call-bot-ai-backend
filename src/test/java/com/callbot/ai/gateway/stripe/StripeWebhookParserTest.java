package com.callbot.ai.gateway.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.callbot.ai.exception.InvalidPaymentSignatureException;
import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.PaymentEvent;
import com.callbot.ai.gateway.PaymentEventType;
import com.stripe.Stripe;

/**
 * The parser is the only place that knows about Stripe signatures and event names, so it
 * is tested directly against a payload signed the way Stripe signs one.
 */
class StripeWebhookParserTest {

    private static final String SECRET = "whsec_test_secret";

    private final StripeProperties properties = new StripeProperties(
            "sk_test", "http://localhost:4200/offre/success", "http://localhost:4200/offre", Map.of(), SECRET);
    private final StripeWebhookParser parser = new StripeWebhookParser(properties);

    @Test
    void parse_checkoutCompleted_returnsNormalisedEvent() {
        String payload = checkoutCompletedPayload("cs_test_123", "sub_123");

        Optional<PaymentEvent> event = parser.parse(payload, Map.of("Stripe-Signature", sign(payload)));

        assertThat(event).isPresent();
        assertThat(event.get().type()).isEqualTo(PaymentEventType.CHECKOUT_COMPLETED);
        assertThat(event.get().checkoutSessionId()).isEqualTo("cs_test_123");
        assertThat(event.get().providerSubscriptionId()).isEqualTo("sub_123");
    }

    @Test
    void parse_lowercaseHeader_isAccepted() {
        String payload = checkoutCompletedPayload("cs_test_123", "sub_123");

        assertThat(parser.parse(payload, Map.of("stripe-signature", sign(payload)))).isPresent();
    }

    @Test
    void parse_unhandledEventType_isDropped() {
        String payload = """
                {"id":"evt_1","object":"event","type":"invoice.paid","api_version":"%s",\
                "data":{"object":{"id":"in_1","object":"invoice"}}}""".formatted(Stripe.API_VERSION);

        assertThat(parser.parse(payload, Map.of("Stripe-Signature", sign(payload)))).isEmpty();
    }

    @Test
    void parse_forgedSignature_isRejected() {
        String payload = checkoutCompletedPayload("cs_test_123", "sub_123");

        assertThatThrownBy(() -> parser.parse(payload, Map.of("Stripe-Signature", "t=1,v1=deadbeef")))
                .isInstanceOf(InvalidPaymentSignatureException.class);
    }

    @Test
    void parse_missingSignatureHeader_isRejected() {
        String payload = checkoutCompletedPayload("cs_test_123", "sub_123");

        assertThatThrownBy(() -> parser.parse(payload, Map.of()))
                .isInstanceOf(InvalidPaymentSignatureException.class);
    }

    @Test
    void parse_withoutConfiguredSecret_reportsMisconfiguration() {
        StripeProperties unconfigured = new StripeProperties("sk_test", "", "", Map.of(), "");

        assertThatThrownBy(() -> new StripeWebhookParser(unconfigured).parse("{}", Map.of()))
                .isInstanceOf(PaymentGatewayException.class);
    }

    private static String checkoutCompletedPayload(String sessionId, String subscriptionId) {
        return """
                {"id":"evt_1","object":"event","type":"checkout.session.completed","api_version":"%s",\
                "data":{"object":{"id":"%s","object":"checkout.session","subscription":"%s"}}}"""
                .formatted(Stripe.API_VERSION, sessionId, subscriptionId);
    }

    /** Rebuilds the {@code Stripe-Signature} header the way Stripe computes it. */
    private static String sign(String payload) {
        long timestamp = Instant.now().getEpochSecond();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));

            return "t=" + timestamp + ",v1=" + hex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append("%02x".formatted(b));
        }

        return hex.toString();
    }
}
