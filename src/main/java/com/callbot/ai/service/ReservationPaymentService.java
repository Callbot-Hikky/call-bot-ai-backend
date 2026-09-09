package com.callbot.ai.service;

import java.time.Duration;
import java.time.OffsetDateTime;
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
import com.callbot.ai.model.Commission;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationCancelledByGuestEvent;
import com.callbot.ai.notification.ReservationConfirmedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.OrganizationRepository;
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
    private final RestaurantRepository restaurantRepository;
    private final OrganizationRepository organizationRepository;
    private final CustomerRepository customerRepository;
    private final StripeConnectGateway connect;
    private final ConnectAccountService connectAccount;
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
        Organization organization = organizationOf(restaurant);
        connectAccount.requireAbleToCharge(organization.getId());

        CheckoutSession session = GuaranteeMode.NO_SHOW.code().equals(reservation.getGuaranteeMode())
                ? registerCard(reservation, restaurant, organization)
                : collectBookingFee(reservation, restaurant, organization);

        reservation.setStripeSessionId(session.id());
        reservationRepository.save(reservation);

        return new PaymentRedirectResponse(session.url());
    }

    private CheckoutSession collectBookingFee(Reservation reservation, Restaurant restaurant,
            Organization organization) {
        int amountCents = reservation.getGuaranteeAmountCents();
        Commission commission = Commission.on(amountCents);
        reservation.setApplicationFeeCents(commission.amountCents());

        // Only one session may be payable at a time.
        connect.expireCheckout(reservation.getStripeSessionId());

        return connect.createBookingFeeCheckout(new BookingFeeCharge(
                reservation.getId(),
                restaurant.getName(),
                amountCents,
                reservation.getCurrency(),
                commission.amountCents(),
                organization.getStripeAccountId(),
                emailOf(reservation)));
    }

    /**
     * The no-show path takes nothing: the diner registers a card and is told so plainly.
     * Alloquence charges no commission on a penalty, so no application fee is set.
     */
    private CheckoutSession registerCard(Reservation reservation, Restaurant restaurant,
            Organization organization) {
        return connect.createCardRegistration(new CardRegistration(
                reservation.getId(),
                restaurant.getName(),
                reservation.getCurrency(),
                organization.getStripeAccountId(),
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

        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        reservation.setPaidAt(OffsetDateTime.now());
        reservation.setStripePaymentIntentId(paid.paymentIntentId());
        reservation.setPayoutEligibleAt(reservation.getEndsAt().plus(PAYOUT_DELAY_AFTER_SERVICE));
        // Single use: the link must not reopen a checkout for a reservation already paid.
        reservation.setPaymentToken(null);
        reservation.setGuaranteeExpiresAt(null);
        reservationRepository.save(reservation);

        events.publishEvent(new ReservationConfirmedEvent(reservation.getId()));
    }

    /**
     * Records a bank dispute against the organization whose fee was contested.
     *
     * <p>Alloquence absorbs the loss, so nothing is claimed back from the restaurateur
     * here. What is kept is the count: an establishment whose diners routinely contest
     * their fees is a risk worth seeing coming rather than discovering on a statement.
     */
    public void recordDispute(ConnectWebhookEvent.DisputeOpened dispute) {
        Reservation reservation = reservationRepository
                .findByStripePaymentIntentId(dispute.paymentIntentId()).orElse(null);
        if (reservation == null) {
            log.warn("Dispute on payment {} matches no reservation", dispute.paymentIntentId());
            return;
        }
        Restaurant restaurant = restaurantOf(reservation);
        Organization organization = organizationOf(restaurant);
        organization.setStripeDisputeCount(organization.getStripeDisputeCount() + 1);
        organization.setStripeLastDisputeAt(OffsetDateTime.now());
        organizationRepository.save(organization);
        log.warn("Dispute of {} cents on reservation {} (organization {}, {} in total)",
                dispute.amountCents(), reservation.getId(), organization.getId(),
                organization.getStripeDisputeCount());
    }

    /**
     * A payment for a reservation already paid for.
     *
     * <p>Normally Stripe simply redelivering the same event, which is nothing. But if it
     * carries a different payment intent, the diner genuinely paid twice — two checkout
     * sessions were open at once — and the second one goes straight back.
     */
    private void refundDuplicate(Reservation reservation, ConnectWebhookEvent.ReservationPaid paid) {
        String alreadyPaid = reservation.getStripePaymentIntentId();
        if (paid.paymentIntentId() == null || paid.paymentIntentId().equals(alreadyPaid)) {
            return;
        }
        log.error("Reservation {} was paid twice ({} and {}); refunding the second payment",
                reservation.getId(), alreadyPaid, paid.paymentIntentId());
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
            return new CancellationResponse(true, reservation.getRefundedAt() != null,
                    reservation.getRefundedAmountCents());
        }
        requireStillCancellable(reservation);

        boolean refundable = isRefundable(reservation);
        if (refundable) {
            // Keyed on the reservation: a retry cannot refund the same fee twice.
            connect.refundFully(reservation.getStripePaymentIntentId(),
                    "refund-" + reservation.getId());
            reservation.setGuaranteeStatus(GuaranteeStatus.REFUNDED);
            reservation.setRefundedAt(OffsetDateTime.now());
            reservation.setRefundedAmountCents(reservation.getGuaranteeAmountCents());
        }

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(OffsetDateTime.now());
        reservation.setPaymentToken(null);
        reservation.setGuaranteeExpiresAt(null);
        reservationRepository.save(reservation);

        events.publishEvent(new ReservationCancelledByGuestEvent(reservation.getId(), refundable));

        return new CancellationResponse(true, refundable, reservation.getRefundedAmountCents());
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
     * A booking fee comes back in full, or not at all — never a share of it. Cancelling
     * late is exactly what the fee exists to discourage, and a partial refund would
     * blunt that while inviting an argument over the fraction.
     */
    private boolean isRefundable(Reservation reservation) {
        if (!GuaranteeStatus.SECURED.equals(reservation.getGuaranteeStatus())
                || reservation.getStripePaymentIntentId() == null) {
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

    private Organization organizationOf(Restaurant restaurant) {
        return organizationRepository.findById(restaurant.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization", restaurant.getOrganizationId()));
    }
}
