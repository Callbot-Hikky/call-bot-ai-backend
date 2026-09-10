package com.callbot.ai.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.CancellationResponse;
import com.callbot.ai.dto.GuestModificationRequest;
import com.callbot.ai.dto.GuestModificationResponse;
import com.callbot.ai.dto.PaymentRedirectResponse;
import com.callbot.ai.dto.PublicModificationResponse;
import com.callbot.ai.dto.PublicReservationResponse;
import com.callbot.ai.dto.PublicTopUpResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.service.PartySizeTopUpService;
import com.callbot.ai.service.ReservationModificationService;
import com.callbot.ai.service.ReservationPaymentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * What an account-less diner can reach, holding nothing but the link they were sent.
 *
 * <p>Unauthenticated by necessity: the diner booked by telephone and has no account.
 * The token is the credential, which is why it is 32 random bytes and never echoed back
 * in an error. Whether it survives being used depends on what it opens: a payment link is
 * spent on the payment it made, while cancelling and modifying are things a diner may
 * come back to.
 */
@RestController
@RequestMapping("/api/public/reservations")
@RequiredArgsConstructor
public class PublicReservationController {

    private final ReservationPaymentService payments;
    private final PartySizeTopUpService topUps;
    private final ReservationModificationService modifications;

    @GetMapping("/paiement/{paymentToken}")
    public PublicReservationResponse describe(@PathVariable String paymentToken) {
        return payments.describe(paymentToken);
    }

    @PostMapping("/paiement/{paymentToken}/checkout")
    public PaymentRedirectResponse checkout(@PathVariable String paymentToken) {
        return payments.startCheckout(paymentToken);
    }

    /**
     * The difference owed after a party grew. A separate token from the one that paid
     * the booking fee: one link must never settle the other's debt.
     */
    @GetMapping("/complement/{paymentToken}")
    public PublicTopUpResponse describeTopUp(@PathVariable String paymentToken) {
        return topUps.describe(paymentToken);
    }

    @PostMapping("/complement/{paymentToken}/checkout")
    public PaymentRedirectResponse topUpCheckout(@PathVariable String paymentToken) {
        return topUps.startCheckout(paymentToken);
    }

    /**
     * The diner's own hand on their covers and their hour.
     *
     * <p>Its token is the one link here that is <em>not</em> single-use: a party may be
     * revised more than once, and consuming it on the first change would mean sending a
     * new link after every one.
     */
    @GetMapping("/modifier/{modificationToken}")
    public PublicModificationResponse describeModifiable(@PathVariable String modificationToken) {
        return modifications.describe(modificationToken);
    }

    /**
     * Slots this reservation could move to. {@code partySize} previews a party the diner
     * is considering but has not committed to — a table for eight is not a table for two,
     * so the picker has to redraw as the number moves.
     */
    @GetMapping("/modifier/{modificationToken}/creneaux")
    public RescheduleSlotsResponse modifiableSlots(@PathVariable String modificationToken,
            @RequestParam(required = false) Integer partySize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate) {
        return modifications.slots(modificationToken, partySize, fromDate);
    }

    @PutMapping("/modifier/{modificationToken}")
    public GuestModificationResponse modify(@PathVariable String modificationToken,
            @Valid @RequestBody GuestModificationRequest request) {
        return modifications.apply(modificationToken, request);
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
