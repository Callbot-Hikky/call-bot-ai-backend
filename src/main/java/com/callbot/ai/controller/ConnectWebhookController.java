package com.callbot.ai.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.gateway.stripe.StripeConnectWebhookParser;
import com.callbot.ai.service.ConnectWebhookHandler;

import lombok.RequiredArgsConstructor;

/**
 * Receives Stripe Connect webhooks: diner payments and connected-account changes.
 *
 * <p>Separate from {@code /api/offers/webhook} because Stripe signs each endpoint with
 * its own secret. Sharing one endpoint would mean one secret for two unrelated money
 * flows, and rotating either would break both.
 */
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class ConnectWebhookController {

    private final StripeConnectWebhookParser parser;
    private final ConnectWebhookHandler handler;

    @PostMapping
    public ResponseEntity<String> handle(@RequestBody String payload,
            @RequestHeader Map<String, String> headers) {
        parser.parse(payload, headers).ifPresent(handler::handle);

        return ResponseEntity.ok("ok");
    }
}
