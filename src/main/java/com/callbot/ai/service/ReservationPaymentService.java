package com.callbot.ai.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CancellationResponse;
import com.callbot.ai.dto.PaymentRedirectResponse;
import com.callbot.ai.dto.PublicReservationResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.stripe.BookingFeeCharge;
import com.callbot.ai.gateway.stripe.CardRegistration;
import com.callbot.ai.gateway.stripe.RegisteredCard;
import com.callbot.ai.gateway.stripe.ConnectWebhookEvent;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Commission;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationCancelledByGuestEvent;
import com.callbot.ai.notification.ReservationConfirmedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * The diner's side of a paid reservation: paying for it, and cancelling it.
 *
 * <p>Every entry point here is reached with a token and nothing else — the diner has
 * no account and never will. The token is therefore the whole of the authorisation, so
 * each method looks the reservation up by token and refuses to work from an id.
 *
 * <p>How long a booking fee waits before reaching the restaurateur's bank is settled
 * here too: paid money becomes payable a day after the service, never before, because
 * a cancellation up to the refund window must still be refundable in full.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ReservationPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ReservationPaymentService.class);

    /** Money stays put until a day after the guests either came or did not. */
    static final Duration PAYOUT_DELAY_AFTER_SERVICE = Duration.ofDays(1);

    private final ReservationRepository reservationRepository;
    private final ReservationChargeRepository charges;
    private final RestaurantRepository restaurantRepository;
    private final CustomerRepository customerRepository;
    private final StripeConnectGateway connect;
    private final ConnectAccountService connectAccount;
    private final PartySizeTopUpService topUps;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public PublicReservationResponse describe(String paymentToken) {
        Reservation reservation = byPaymentToken(paymentToken);
        return PublicReservationResponse.of(reservation, restaurantOf(reservation));
    }

    @Transactional(readOnly = true)
    public PublicReservationResponse describeCancellable(String cancellationToken) {
        Reservation reservation = byCancellationToken(cancellationToken);
        return PublicReservationResponse.of(reservation, restaurantOf(reservation));
    }

    /**
     * Opens the hosted page that collects the booking fee.
     *
     * <p>A fresh Stripe session is created on every call rather than reusing a stored
     * one: a diner who left the page and came back would otherwise land on a session
     * Stripe may already have expired. The previous session is expired first — two live
     * sessions for one reservation would both be payable, and the second payment would
     * have to be handed back by hand.
     */
    public PaymentRedirectResponse startCheckout(String paymentToken) {
        Reservation reservation = byPaymentToken(paymentToken);
        Restaurant restaurant = restaurantOf(reservation);

        requireAwaitingGuarantee(reservation);
        connectAccount.requireAbleToCharge(restaurant.getId());

        if (GuaranteeMode.NO_SHOW.code().equals(reservation.getGuaranteeMode())) {
            return new PaymentRedirectResponse(registerCard(reservation, restaurant).url());
        }
        return new PaymentRedirectResponse(collectBookingFee(reservation, restaurant).url());
    }

    /**
     * Opens, or reopens, the one charge that stands for this reservation's booking fee.
     *
     * <p>Reopened rather than added to: a diner who came back to the link is settling the
     * same debt, and a second row would claim they owe twice. The row is only created
     * once, and carries the session Stripe is holding for it.
     */
    private CheckoutSession collectBookingFee(Reservation reservation, Restaurant restaurant) {
        int amountCents = reservation.getGuaranteeAmountCents();
        Commission commission = Commission.on(amountCents);

        ReservationCharge charge = charges
                .findByReservationIdAndKindAndStatus(
                        reservation.getId(), ChargeKind.BOOKING_FEE, ChargeStatus.PENDING)
                .orElseGet(() -> ReservationCharge.builder()
                        .reservationId(reservation.getId())
                        .kind(ChargeKind.BOOKING_FEE)
                        .status(ChargeStatus.PENDING)
                        .build());

        // Only one session may be payable at a time.
        connect.expireCheckout(charge.getStripeSessionId());

        CheckoutSession session = connect.createBookingFeeCheckout(new BookingFeeCharge(
                reservation.getId(),
                restaurant.getName(),
                amountCents,
                reservation.getCurrency(),
                commission.amountCents(),
                restaurant.getStripeAccountId(),
                emailOf(reservation)));

        charge.setAmountCents(amountCents);
        charge.setApplicationFeeCents(commission.amountCents());
        charge.setCurrency(reservation.getCurrency());
        charge.setStripeSessionId(session.id());
        charges.save(charge);

        return session;
    }

    /**
     * The no-show path takes nothing: the diner registers a card and is told so plainly.
     * Alloquence charges no commission on a penalty, so no application fee is set.
     */
    private CheckoutSession registerCard(Reservation reservation, Restaurant restaurant) {
        return connect.createCardRegistration(new CardRegistration(
                reservation.getId(),
                restaurant.getName(),
                reservation.getCurrency(),
                restaurant.getStripeAccountId(),
                emailOf(reservation)));
    }

    /**
     * Records the card a diner registered, which confirms their reservation without a
     * cent changing hands.
     */
    public void recordRegisteredCard(ConnectWebhookEvent.CardRegistered registered) {
        Reservation reservation = reservationRepository.findById(registered.reservationId()).orElse(null);
        if (reservation == null) {
            log.warn("Stripe reported a card for unknown reservation {}", registered.reservationId());
            return;
        }
        if (GuaranteeStatus.SECURED.equals(reservation.getGuaranteeStatus())) {
            return;
        }
        if (ReservationStatus.CANCELLED.equals(reservation.getStatus())) {
            log.warn("Reservation {} had its card registered after being released",
                    reservation.getId());
            return;
        }

        RegisteredCard card = connect.readRegisteredCard(
                registered.setupIntentId(), registered.connectedAccountId());

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        reservation.setStripeSetupIntentId(registered.setupIntentId());
        reservation.setStripeCustomerId(card.customerId());
        reservation.setStripePaymentMethodId(card.paymentMethodId());
        // Nothing was paid, so there is nothing to pay out and nothing to refund.
        reservation.setPaymentToken(null);
        reservation.setGuaranteeExpiresAt(null);
        reservationRepository.save(reservation);

        events.publishEvent(new ReservationConfirmedEvent(reservation.getId()));
    }

    /**
     * Confirms a reservation Stripe says is paid.
     *
     * <p>Stripe retries webhooks until it gets a 2xx, and may deliver the same event
     * twice, so a reservation already secured is left exactly as it is — confirming it
     * again would send the diner a second confirmation and restart the payout clock.
     */
    public void markPaid(ConnectWebhookEvent.ReservationPaid paid) {
        // A reservation can now carry more than one payable checkout, and Stripe's
        // metadata only names the reservation. The session says which movement this was.
        ReservationCharge bySession = paid.sessionId() == null
                ? null
                : charges.findByStripeSessionId(paid.sessionId()).orElse(null);
        if (bySession != null && bySession.isPartySizeTopUp()) {
            topUps.settle(bySession, paid.paymentIntentId());
            return;
        }

        Reservation reservation = reservationRepository.findById(paid.reservationId()).orElse(null);
        if (reservation == null) {
            log.warn("Stripe reported a payment for unknown reservation {}", paid.reservationId());
            return;
        }
        if (GuaranteeStatus.SECURED.equals(reservation.getGuaranteeStatus())) {
            refundDuplicate(reservation, paid);
            return;
        }
        if (ReservationStatus.CANCELLED.equals(reservation.getStatus())) {
            // The window closed while the diner was on the payment page. Their table is
            // gone and someone must give the money back by hand; refunding here would
            // race the sweep that just released it.
            log.error("Reservation {} was paid after being released; a manual refund is due",
                    reservation.getId());
            return;
        }

        settleBookingFee(reservation, paid.paymentIntentId());

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        // Single use: the link must not reopen a checkout for a reservation already paid.
        reservation.setPaymentToken(null);
        reservation.setGuaranteeExpiresAt(null);
        reservationRepository.save(reservation);

        events.publishEvent(new ReservationConfirmedEvent(reservation.getId()));
    }

    /**
     * Closes the pending booking-fee charge, or writes one if the checkout never opened
     * through us — money that arrived is money that belongs in the register either way.
     */
    private void settleBookingFee(Reservation reservation, String paymentIntentId) {
        int amountCents = reservation.getGuaranteeAmountCents() == null
                ? 0
                : reservation.getGuaranteeAmountCents();

        ReservationCharge charge = charges
                .findByReservationIdAndKindAndStatus(
                        reservation.getId(), ChargeKind.BOOKING_FEE, ChargeStatus.PENDING)
                .orElseGet(() -> ReservationCharge.builder()
                        .reservationId(reservation.getId())
                        .kind(ChargeKind.BOOKING_FEE)
                        .amountCents(amountCents)
                        .applicationFeeCents(Commission.on(amountCents).amountCents())
                        .currency(reservation.getCurrency())
                        .build());

        charge.setStatus(ChargeStatus.PAID);
        charge.setPaidAt(OffsetDateTime.now());
        charge.setStripePaymentIntentId(paymentIntentId);
        charge.setPayoutEligibleAt(reservation.getEndsAt().plus(PAYOUT_DELAY_AFTER_SERVICE));
        charges.save(charge);
    }

    /**
     * Records a bank dispute against the organization whose fee was contested.
     *
     * <p>Alloquence absorbs the loss, so nothing is claimed back from the restaurateur
     * here. What is kept is the count: an establishment whose diners routinely contest
     * their fees is a risk worth seeing coming rather than discovering on a statement.
     */
    public void recordDispute(ConnectWebhookEvent.DisputeOpened dispute) {
        ReservationCharge charge = charges
                .findByStripePaymentIntentId(dispute.paymentIntentId()).orElse(null);
        if (charge == null) {
            log.warn("Dispute on payment {} matches no charge", dispute.paymentIntentId());
            return;
        }
        if (!charge.isBookingFee()) {
            // A contested no-show penalty is not a loss Alloquence absorbs, having taken
            // no commission on it. It is the restaurateur's to argue.
            log.warn("Dispute of {} cents on penalty {} is not counted against the restaurant",
                    dispute.amountCents(), charge.getId());
            return;
        }
        Reservation reservation = reservationRepository
                .findById(charge.getReservationId()).orElse(null);
        if (reservation == null) {
            log.warn("Dispute on payment {} matches no reservation", dispute.paymentIntentId());
            return;
        }
        Restaurant restaurant = restaurantOf(reservation);
        connectAccount.recordDispute(restaurant);
        log.warn("Dispute of {} cents on reservation {} (restaurant {}, {} in total)",
                dispute.amountCents(), reservation.getId(), restaurant.getId(),
                restaurant.getStripeDisputeCount());
    }

    /**
     * A payment for a reservation already paid for.
     *
     * <p>Normally Stripe simply redelivering the same event, which is nothing. But if it
     * carries a different payment intent, the diner genuinely paid twice — two checkout
     * sessions were open at once — and the second one goes straight back.
     */
    private void refundDuplicate(Reservation reservation, ConnectWebhookEvent.ReservationPaid paid) {
        if (paid.paymentIntentId() == null) {
            return;
        }
        boolean alreadyInRegister = charges.findByReservationId(reservation.getId()).stream()
                .anyMatch(charge -> paid.paymentIntentId().equals(charge.getStripePaymentIntentId()));
        if (alreadyInRegister) {
            return;
        }
        log.error("Reservation {} was paid twice; refunding payment {}",
                reservation.getId(), paid.paymentIntentId());
        connect.refundFully(paid.paymentIntentId(), "duplicate-" + paid.paymentIntentId());
    }

    /**
     * Cancels on the diner's own initiative, refunding when they are still inside the
     * window they were promised.
     *
     * <p>The window frozen on the reservation is the one that counts, not the
     * restaurant's current setting: it is what they were told when they paid.
     */
    public CancellationResponse cancelByToken(String cancellationToken) {
        Reservation reservation = byCancellationToken(cancellationToken);

        if (ReservationStatus.CANCELLED.equals(reservation.getStatus())) {
            int alreadyRefunded = refundedTotalOf(reservation);
            return new CancellationResponse(true, alreadyRefunded > 0, alreadyRefunded);
        }
        requireStillCancellable(reservation);

        // A request still outstanding never collected anything, so there is nothing to
        // give back — only a live link to close before the diner pays for a service that
        // is not happening. One already settled is money, and goes through the refund
        // rules below with the rest: it needs no handling of its own.
        topUps.lapsePendingFor(reservation.getId(), "the diner cancelled the reservation");

        int refunded = isRefundable(reservation) ? refundEverythingPaid(reservation) : 0;

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(OffsetDateTime.now());
        reservation.setPaymentToken(null);
        reservation.setGuaranteeExpiresAt(null);
        reservationRepository.save(reservation);

        events.publishEvent(
                new ReservationCancelledByGuestEvent(reservation.getId(), refunded > 0, refunded));

        return new CancellationResponse(true, refunded > 0, refunded);
    }

    /**
     * Gives back every charge settled on this reservation, and reports the total.
     *
     * <p>Every one of them, because a party that grew paid twice: refunding only the
     * booking fee would keep the top-up for a table nobody will sit at. Each refund is
     * keyed on its own charge, so a retry cannot give the same money back twice.
     */
    private int refundEverythingPaid(Reservation reservation) {
        List<ReservationCharge> paid = charges
                .findByReservationIdAndStatus(reservation.getId(), ChargeStatus.PAID);
        if (paid.isEmpty()) {
            return 0;
        }

        OffsetDateTime now = OffsetDateTime.now();
        int total = 0;
        for (ReservationCharge charge : paid) {
            connect.refundFully(charge.getStripePaymentIntentId(), "refund-" + charge.getId());
            charge.markRefundedInFull(now);
            total += charge.getAmountCents();
        }
        charges.saveAll(paid);
        reservation.setGuaranteeStatus(GuaranteeStatus.REFUNDED);

        return total;
    }

    private int refundedTotalOf(Reservation reservation) {
        return charges.findByReservationIdAndStatus(reservation.getId(), ChargeStatus.REFUNDED)
                .stream()
                .mapToInt(charge -> charge.getRefundedAmountCents() == null
                        ? 0
                        : charge.getRefundedAmountCents())
                .sum();
    }

    /**
     * The cancellation link is valid until the service, and no further.
     *
     * <p>Afterwards the table was either used or wasted, and flipping the reservation to
     * cancelled would rewrite what happened in the dining room — freeing a table on the
     * floor plan that was in fact occupied, on the strength of a message anyone may
     * still have.
     */
    private void requireStillCancellable(Reservation reservation) {
        if (reservation.getStartsAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidRequestException(
                    "Le service a déjà eu lieu : ce lien n'est plus valable");
        }
    }

    /**
     * Whether <em>cancelling</em> gives the fee back. All of it, or none — never a share.
     * Cancelling late is exactly what the fee exists to discourage, and handing part of it
     * back would blunt that while inviting an argument over the fraction.
     *
     * <p>This governs cancellation only. A party that merely shrinks is not cancelling:
     * it does give its covers back, whatever the refund window says, through
     * {@link PartySizeRefund}.
     */
    private boolean isRefundable(Reservation reservation) {
        if (!GuaranteeStatus.SECURED.equals(reservation.getGuaranteeStatus())) {
            return false;
        }
        if (!GuaranteeMode.BOOKING_FEE.code().equals(reservation.getGuaranteeMode())) {
            return false;
        }
        int windowHours = reservation.getGuaranteeRefundWindowHours() == null
                ? 0
                : reservation.getGuaranteeRefundWindowHours();
        return OffsetDateTime.now().isBefore(reservation.getStartsAt().minusHours(windowHours));
    }

    private void requireAwaitingGuarantee(Reservation reservation) {
        if (GuaranteeMode.NONE.code().equals(reservation.getGuaranteeMode())) {
            throw new InvalidRequestException("Cette réservation n'attend aucune garantie");
        }
        if (!GuaranteeStatus.AWAITING.equals(reservation.getGuaranteeStatus())
                || !ReservationStatus.AWAITING_PAYMENT.equals(reservation.getStatus())) {
            throw new InvalidRequestException("Cette réservation n'attend plus de règlement");
        }
        if (reservation.getGuaranteeExpiresAt() != null
                && reservation.getGuaranteeExpiresAt().isBefore(OffsetDateTime.now())) {
            // The sweep has not run yet, but the table is no longer the diner's to buy.
            throw new InvalidRequestException("Le délai de règlement est écoulé");
        }
        if (reservation.getGuaranteeAmountCents() == null || reservation.getGuaranteeAmountCents() <= 0) {
            throw new IllegalStateException(
                    "Reservation " + reservation.getId() + " awaits payment without an amount");
        }
    }

    private String emailOf(Reservation reservation) {
        if (reservation.getCustomerId() == null) {
            return null;
        }
        return customerRepository.findById(reservation.getCustomerId())
                .map(Customer::getEmail)
                .orElse(null);
    }

    /** The token itself never travels back in the error: it is a secret, and errors are logged. */
    private Reservation byPaymentToken(String paymentToken) {
        return reservationRepository.findByPaymentToken(paymentToken)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "this payment link"));
    }

    private Reservation byCancellationToken(String cancellationToken) {
        return reservationRepository.findByCancellationToken(cancellationToken)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "this cancellation link"));
    }

    private Restaurant restaurantOf(Reservation reservation) {
        return restaurantRepository.findById(reservation.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", reservation.getRestaurantId()));
    }

}
