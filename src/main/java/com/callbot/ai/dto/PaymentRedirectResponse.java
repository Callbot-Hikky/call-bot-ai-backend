package com.callbot.ai.dto;

/** Where the browser must go to pay: Stripe's hosted page. */
public record PaymentRedirectResponse(String url) {
}
