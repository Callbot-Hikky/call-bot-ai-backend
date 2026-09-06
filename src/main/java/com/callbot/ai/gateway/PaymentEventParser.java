package com.callbot.ai.gateway;

import java.util.Map;
import java.util.Optional;

/**
 * Outbound port for inbound webhooks: authenticates the raw provider callback and turns
 * it into a {@link PaymentEvent}. Keeps signature verification and payload parsing —
 * both provider-specific — out of the web layer.
 */
public interface PaymentEventParser {

    /**
     * Verifies the callback's authenticity and normalises it.
     *
     * @param payload the raw request body, unaltered (signatures are computed over bytes)
     * @param headers the request headers; the adapter picks the signature header it needs
     * @return the normalised event, or empty when the notification is one we ignore
     * @throws com.callbot.ai.exception.InvalidPaymentSignatureException if the signature
     *         does not match
     * @throws com.callbot.ai.exception.PaymentGatewayException if webhook verification is
     *         not configured
     */
    Optional<PaymentEvent> parse(String payload, Map<String, String> headers);
}
