package com.callbot.ai.service;

import org.springframework.stereotype.Service;

import com.callbot.ai.gateway.stripe.ConnectWebhookEvent;

import lombok.RequiredArgsConstructor;

/**
 * Routes a verified Stripe Connect event to whoever acts on it.
 *
 * <p>The single place that knows the whole set: adding an event type here fails to
 * compile until it is handled, because the event is a sealed type.
 */
@Service
@RequiredArgsConstructor
public class ConnectWebhookHandler {

    private final ReservationPaymentService payments;
    private final ConnectAccountService accounts;

    public void handle(ConnectWebhookEvent event) {
        switch (event) {
            case ConnectWebhookEvent.ReservationPaid paid -> payments.markPaid(paid);
            case ConnectWebhookEvent.AccountUpdated updated -> accounts.apply(updated.status());
            case ConnectWebhookEvent.CardRegistered card -> payments.recordRegisteredCard(card);
            case ConnectWebhookEvent.DisputeOpened dispute -> payments.recordDispute(dispute);
        }
    }
}
