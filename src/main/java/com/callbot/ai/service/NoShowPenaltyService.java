package com.callbot.ai.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.gateway.stripe.NoShowCharge;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationPenaltyAbandonedEvent;
import com.callbot.ai.notification.ReservationPenaltyChargedEvent;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * The transactional half of collecting no-show penalties, and of forgetting the cards
 * once they can no longer be needed.
 *
 * <p>Like the payout sweep, claim and settle are separate short transactions with the
 * Stripe call in between, orchestrated by {@link NoShowPenaltyJob} — a transaction held
 * open across a bank authorisation would lock the row for as long as the bank takes.
 */
@Service
@RequiredArgsConstructor
public class NoShowPenaltyService {

    private static final Logger log = LoggerFactory.getLogger(NoShowPenaltyService.class);

    /** Two goes. A card that has refused twice a day apart is not going to say yes. */
    public static final int MAX_ATTEMPTS = 2;

    /** Long enough for a topped-up account or a lifted block to make a difference. */
    public static final Duration RETRY_DELAY = Duration.ofDays(1);

    /** Cards are kept a little past the service, then forgotten. */
    static final Duration CARD_RETENTION_AFTER_SERVICE = Duration.ofDays(2);

    private final ReservationRepository reservationRepository;
    private final RestaurantRepository restaurantRepository;
    private final OrganizationRepository organizationRepository;
    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reservation> claimDuePenalties(OffsetDateTime now) {
        return reservationRepository.lockDuePenalties(now, MAX_ATTEMPTS);
    }

    /** The account the penalty is charged on: the restaurant's own, never Alloquence's. */
    @Transactional(readOnly = true)
    public Optional<String> connectedAccountFor(Reservation reservation) {
        return restaurantRepository.findById(reservation.getRestaurantId())
                .map(Restaurant::getOrganizationId)
                .flatMap(organizationRepository::findById)
                .map(Organization::getStripeAccountId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleCharged(UUID reservationId, String paymentIntentId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        reservation.setGuaranteeStatus(GuaranteeStatus.CHARGED);
        reservation.setStripePaymentIntentId(paymentIntentId);
        reservation.setPenaltyChargedAt(now);
        reservation.setPenaltyDueAt(null);
        reservation.setPenaltyAttempts(reservation.getPenaltyAttempts() + 1);
        // The money is the restaurateur's and owes Alloquence nothing, so it joins the
        // payout sweep at once rather than waiting a day after a service already past.
        reservation.setPaidAt(now);
        reservation.setApplicationFeeCents(0);
        reservation.setPayoutEligibleAt(now);
        reservationRepository.save(reservation);

        events.publishEvent(new ReservationPenaltyChargedEvent(reservationId));
    }

    /**
     * Records a refusal. The last one gives up for good and tells the restaurateur, who
     * is the only person who can do anything about it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleFailed(UUID reservationId, String reason) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return;
        }
        int attempts = reservation.getPenaltyAttempts() + 1;
        reservation.setPenaltyAttempts(attempts);

        if (attempts >= MAX_ATTEMPTS) {
            reservation.setGuaranteeStatus(GuaranteeStatus.CHARGE_FAILED);
            reservation.setPenaltyDueAt(null);
            reservationRepository.save(reservation);
            log.warn("Giving up on the penalty for reservation {} after {} attempts: {}",
                    reservationId, attempts, reason);
            events.publishEvent(new ReservationPenaltyAbandonedEvent(reservationId, reason));
            return;
        }

        reservation.setPenaltyDueAt(OffsetDateTime.now().plus(RETRY_DELAY));
        reservationRepository.save(reservation);
        log.info("Penalty for reservation {} refused ({}); retrying in {}",
                reservationId, reason, RETRY_DELAY);
    }

    @Transactional(readOnly = true)
    public List<Reservation> cardsToForget(OffsetDateTime now) {
        return reservationRepository.findCardsToDetach(
                now.minus(CARD_RETENTION_AFTER_SERVICE), MAX_ATTEMPTS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCardForgotten(UUID reservationId) {
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            reservation.setPaymentMethodDetachedAt(OffsetDateTime.now());
            reservation.setStripePaymentMethodId(null);
            reservationRepository.save(reservation);
        });
    }

    /** Everything Stripe needs, or nothing if the reservation is not chargeable after all. */
    public Optional<NoShowCharge> chargeFor(Reservation reservation, String connectedAccountId) {
        if (reservation.getGuaranteeAmountCents() == null
                || reservation.getStripePaymentMethodId() == null
                || reservation.getStripeCustomerId() == null
                || connectedAccountId == null) {
            return Optional.empty();
        }
        return Optional.of(new NoShowCharge(
                reservation.getId(),
                reservation.getGuaranteeAmountCents(),
                reservation.getCurrency(),
                reservation.getStripeCustomerId(),
                reservation.getStripePaymentMethodId(),
                connectedAccountId,
                // Keyed on the attempt: a retry is a new authorisation, but a repeat of
                // the same attempt after a lost answer is not a second debit.
                "penalty-" + reservation.getId() + "-" + reservation.getPenaltyAttempts()));
    }
}
