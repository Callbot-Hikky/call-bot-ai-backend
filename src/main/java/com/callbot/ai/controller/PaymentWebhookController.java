package com.callbot.ai.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.gateway.PaymentEventParser;
import com.callbot.ai.service.OfferService;

import lombok.RequiredArgsConstructor;

/**
 * Receives payment provider webhooks. Public endpoint (the provider calls it
 * unauthenticated): trust comes from the signature header, verified by the adapter.
 *
 * <p>Provider-agnostic on purpose — it hands the raw body and headers to the configured
 * {@link PaymentEventParser} and acts on the normalised event, so switching provider
 * leaves this class untouched.
 */
@RestController
@RequestMapping("/api/offers/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentEventParser eventParser;
    private final OfferService offerService;

    @PostMapping
    public ResponseEntity<String> handle(@RequestBody String payload,
            @RequestHeader Map<String, String> headers) {
        eventParser.parse(payload, headers)
                .ifPresent(event -> offerService.handle(event));

        return ResponseEntity.ok("ok");
    }
}
