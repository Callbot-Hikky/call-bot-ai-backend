package com.callbot.ai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.CancellationResponse;
import com.callbot.ai.dto.PaymentRedirectResponse;
import com.callbot.ai.dto.PublicReservationResponse;
import com.callbot.ai.service.ReservationPaymentService;

import lombok.RequiredArgsConstructor;

/**
 * What an account-less diner can reach, holding nothing but the link they were sent.
 *
 * <p>Unauthenticated by necessity: the diner booked by telephone and has no account.
 * The token is the credential, which is why it is 32 random bytes, single-use for
 * payment, and never echoed back in an error.
 */
@RestController
@RequestMapping("/api/public/reservations")
@RequiredArgsConstructor
public class PublicReservationController {

    private final ReservationPaymentService payments;

    @GetMapping("/paiement/{paymentToken}")
    public PublicReservationResponse describe(@PathVariable String paymentToken) {
        return payments.describe(paymentToken);
    }

    @PostMapping("/paiement/{paymentToken}/checkout")
    public PaymentRedirectResponse checkout(@PathVariable String paymentToken) {
        return payments.startCheckout(paymentToken);
    }

    @GetMapping("/annulation/{cancellationToken}")
    public PublicReservationResponse describeCancellable(@PathVariable String cancellationToken) {
        return payments.describeCancellable(cancellationToken);
    }

    @PostMapping("/annulation/{cancellationToken}")
    public CancellationResponse cancel(@PathVariable String cancellationToken) {
        return payments.cancelByToken(cancellationToken);
    }
}
