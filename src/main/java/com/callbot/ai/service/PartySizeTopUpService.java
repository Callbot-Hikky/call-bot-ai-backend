package com.callbot.ai.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.PaymentRedirectResponse;
import com.callbot.ai.dto.PublicTopUpResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.stripe.PartySizeTopUpCharge;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Commission;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationTopUpRequestedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * The difference owed when a party grows on a reservation whose fee was priced per guest.
 *
 * <p>Same mechanics as the first payment, applied a second time: a pending charge, a
 * fresh single-use link, a short window, and a message telling the diner what is owed
 * and why. What differs is that <em>nothing is held</em> while they decide. The
 * reservation stays at its current size, on its current table, confirmed; a table was
 * merely observed to be free when the rise was asked for, and that observation is made
 * again when the money lands.
 *
 * <p>The top-up is priced off the unit amount frozen when the reservation was taken, not
 * off the restaurant's current setting: a restaurateur raising their fee afterwards must
 * not reprice a table already sold.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PartySizeTopUpService {

    private static final Logger log = LoggerFactory.getLogger(PartySizeTopUpService.class);

    /** How long the diner has to settle. The same window they were given to pay at all. */
    public static final Duration SETTLEMENT_WINDOW = GuaranteePolicy.PAYMENT_WINDOW;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReservationRepository reservationRepository;
    private final ReservationChargeRepository charges;
    private final RestaurantRepository restaurantRepository;
    private final CustomerRepository customerRepository;
    private final StripeConnectGateway connect;
    private final ApplicationEventPublisher events;

    /**
     * Opens the request. The reservation is left untouched, deliberately.
     *
     * @param reservation      the reservation as stored, still at its current party size
     * @param targetPartySize  the larger party the diner asked for
     * @return the charge now awaiting settlement
     */
    public ReservationCharge open(Reservation reservation, int targetPartySize) {
        int current = reservation.getPartySize();
        int extraGuests = targetPartySize - current;
        int perGuest = requireFrozenUnitPrice(reservation);
        int amountCents = perGuest * extraGuests;

        ReservationCharge charge = ReservationCharge.builder()
                .reservationId(reservation.getId())
                .kind(ChargeKind.PARTY_SIZE_TOP_UP)
                .status(ChargeStatus.PENDING)
                .amountCents(amountCents)
                // A top-up extends the booking fee, so it carries the same commission.
                .applicationFeeCents(Commission.on(amountCents).amountCents())
                .currency(reservation.getCurrency())
                .paymentToken(newToken())
                .tokenExpiresAt(OffsetDateTime.now().plus(SETTLEMENT_WINDOW))
                .targetPartySize(targetPartySize)
                .build();

        try {
            charge = charges.saveAndFlush(charge);
        } catch (DataIntegrityViolationException e) {
            // The partial unique index caught a second request racing the first. Same
            // answer as the rule gives when it sees one already there.
            throw new PartySizeChangeRejectedException(
                    PartySizeChangeRejectedException.TOP_UP_PENDING,
                    "A top-up is already awaiting settlement on this reservation");
        }

        events.publishEvent(
                new ReservationTopUpRequestedEvent(reservation.getId(), charge.getId()));
        return charge;
    }

    /** What the diner is shown when they follow their link. */
    @Transactional(readOnly = true)
    public PublicTopUpResponse describe(String paymentToken) {
        ReservationCharge charge = byPaymentToken(paymentToken);
        Reservation reservation = reservationOf(charge);
        return PublicTopUpResponse.of(charge, reservation, restaurantOf(reservation));
    }

    /**
     * Opens, or reopens, the hosted page that collects the top-up.
     *
     * <p>A fresh Stripe session on every call, the previous one expired first: two live
     * sessions for one charge would both be payable, and the second would have to be
     * handed back by hand.
     */
    public PaymentRedirectResponse startCheckout(String paymentToken) {
        ReservationCharge charge = byPaymentToken(paymentToken);
        requireStillOpen(charge);

        Reservation reservation = reservationOf(charge);
        Restaurant restaurant = restaurantOf(reservation);

        connect.expireCheckout(charge.getStripeSessionId());

        CheckoutSession session = connect.createPartySizeTopUpCheckout(new PartySizeTopUpCharge(
                reservation.getId(),
                restaurant.getName(),
                charge.getTargetPartySize() - reservation.getPartySize(),
                charge.getAmountCents(),
                charge.getCurrency(),
                charge.getApplicationFeeCents(),
                restaurant.getStripeAccountId(),
                emailOf(reservation)));

        charge.setStripeSessionId(session.id());
        charges.save(charge);

        return new PaymentRedirectResponse(session.url());
    }

    /**
     * Records that the money for a top-up arrived.
     *
     * <p>Only the money is recorded here. Whether the larger party can actually be
     * seated is asked again at that point, and answering it — moving the reservation, or
     * handing the money straight back when no table is left — is the next piece of work.
     * The register is right either way: money that came in belongs in it.
     */
    public void markPaid(ReservationCharge charge, String paymentIntentId) {
        if (charge.isPaid()) {
            // Stripe retries until it gets a 2xx and may deliver the same event twice.
            return;
        }
        Reservation reservation = reservationOf(charge);

        charge.setStatus(ChargeStatus.PAID);
        charge.setPaidAt(OffsetDateTime.now());
        charge.setStripePaymentIntentId(paymentIntentId);
        charge.setPayoutEligibleAt(
                reservation.getEndsAt().plus(ReservationPaymentService.PAYOUT_DELAY_AFTER_SERVICE));
        // Single use: the link must not reopen a checkout for a top-up already settled.
        charge.setPaymentToken(null);
        charges.save(charge);

        log.info("Top-up {} settled on reservation {}: {} covers awaiting a table",
                charge.getId(), reservation.getId(), charge.getTargetPartySize());
    }

    /**
     * The unit price the reservation was sold at.
     *
     * <p>Absent only on rows written before the price was frozen per guest, where it can
     * still be recovered exactly: the total was that price times the party as it stood.
     */
    private int requireFrozenUnitPrice(Reservation reservation) {
        if (reservation.getGuaranteeCentsPerGuest() != null) {
            return reservation.getGuaranteeCentsPerGuest();
        }
        Integer total = reservation.getGuaranteeAmountCents();
        Integer partySize = reservation.getPartySize();
        if (total == null || partySize == null || partySize <= 0) {
            throw new IllegalStateException("Reservation " + reservation.getId()
                    + " carries a booking fee without a price per guest");
        }
        return total / partySize;
    }

    private void requireStillOpen(ReservationCharge charge) {
        if (!charge.isOpenFor(OffsetDateTime.now())) {
            throw new InvalidRequestException("Le délai de règlement du complément est écoulé");
        }
    }

    /** The token itself never travels back in the error: it is a secret, and errors are logged. */
    private ReservationCharge byPaymentToken(String paymentToken) {
        return charges.findByPaymentToken(paymentToken)
                .filter(ReservationCharge::isPartySizeTopUp)
                .orElseThrow(() -> new ResourceNotFoundException("Complément", "this payment link"));
    }

    private Reservation reservationOf(ReservationCharge charge) {
        return reservationRepository.findById(charge.getReservationId())
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", charge.getReservationId()));
    }

    private Restaurant restaurantOf(Reservation reservation) {
        return restaurantRepository.findById(reservation.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", reservation.getRestaurantId()));
    }

    private String emailOf(Reservation reservation) {
        if (reservation.getCustomerId() == null) {
            return null;
        }
        return customerRepository.findById(reservation.getCustomerId())
                .map(Customer::getEmail)
                .orElse(null);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
