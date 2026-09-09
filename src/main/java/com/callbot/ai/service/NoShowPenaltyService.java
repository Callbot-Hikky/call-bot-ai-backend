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
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationPenaltyAbandonedEvent;
import com.callbot.ai.notification.ReservationPenaltyChargedEvent;
import com.callbot.ai.repository.ReservationChargeRepository;
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
    public static final Duration CARD_RETENTION_AFTER_SERVICE = Duration.ofDays(2);

    /** Same rule as a booking fee: nothing leaves for the bank before then. */
    private static final Duration PAYOUT_DELAY_AFTER_SERVICE = Duration.ofDays(1);

    private final ReservationRepository reservationRepository;
    private final ReservationChargeRepository charges;
    private final RestaurantRepository restaurantRepository;
    private final ApplicationEventPublisher events;

    /**
     * Takes ownership of the penalties that are due, and commits that ownership.
     *
     * <p>The row lock alone would be worthless here: it dies with this transaction, and
     * the Stripe call happens after. What makes the claim exclusive is the write — the
     * attempt is counted and the due date cleared, so a second instance scanning a
     * moment later no longer sees these rows and cannot debit the same card twice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Reservation> claimDuePenalties(OffsetDateTime now) {
        List<Reservation> due = reservationRepository.lockDuePenalties(
                now, MAX_ATTEMPTS, ChargeKind.NO_SHOW_PENALTY, ChargeStatus.PAID);
        for (Reservation reservation : due) {
            reservation.setPenaltyAttempts(reservation.getPenaltyAttempts() + 1);
            reservation.setPenaltyDueAt(null);
        }
        return reservationRepository.saveAll(due);
    }

    /** The account the penalty is charged on: the restaurant's own, never Alloquence's. */
    @Transactional(readOnly = true)
    public Optional<String> connectedAccountFor(Reservation reservation) {
        return restaurantRepository.findById(reservation.getRestaurantId())
                .map(Restaurant::getStripeAccountId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleCharged(UUID reservationId, String paymentIntentId) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return;
        }
        if (!ReservationStatus.NO_SHOW.equals(reservation.getStatus())) {
            // Staff took the absence back while the bank was answering. The money has
            // left, so this is not something to paper over: it is recorded loudly and
            // given back by hand, rather than quietly marking a diner absent again.
            log.error("Reservation {} was charged {} but its absence had been retracted; "
                    + "a manual refund is due", reservationId, paymentIntentId);
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        reservation.setGuaranteeStatus(GuaranteeStatus.CHARGED);
        reservation.setPenaltyDueAt(null);
        reservationRepository.save(reservation);

        // The penalty is an entry in the register like any other, save for one thing: the
        // money is entirely the restaurateur's and owes Alloquence no commission
        // (decision 29). It follows the same rule as every other sum for the rest —
        // payable a day after the service.
        charges.save(ReservationCharge.builder()
                .reservationId(reservationId)
                .kind(ChargeKind.NO_SHOW_PENALTY)
                .status(ChargeStatus.PAID)
                .amountCents(reservation.getGuaranteeAmountCents())
                .applicationFeeCents(0)
                .currency(reservation.getCurrency())
                .stripePaymentIntentId(paymentIntentId)
                .paidAt(now)
                .payoutEligibleAt(reservation.getEndsAt().plus(PAYOUT_DELAY_AFTER_SERVICE))
                .build());

        events.publishEvent(new ReservationPenaltyChargedEvent(reservationId));
    }

    /**
     * Gives up at once, without spending a retry.
     *
     * <p>For conditions a day's wait cannot change — no card on file, no account able to
     * receive the money. Treating those as bank refusals would keep a restaurateur
     * waiting two days for news that was already certain.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(UUID reservationId, String reason) {
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            reservation.setGuaranteeStatus(GuaranteeStatus.CHARGE_FAILED);
            reservation.setPenaltyDueAt(null);
            reservationRepository.save(reservation);
            log.warn("Nothing to charge for reservation {}: {}", reservationId, reason);
            events.publishEvent(new ReservationPenaltyAbandonedEvent(reservationId, reason));
        });
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
        int attempts = reservation.getPenaltyAttempts();

        if (attempts >= MAX_ATTEMPTS) {
            reservation.setGuaranteeStatus(GuaranteeStatus.CHARGE_FAILED);
            reservation.setPenaltyDueAt(null);
            reservationRepository.save(reservation);
            log.warn("Giving up on the penalty for reservation {} after {} attempts: {}",
                    reservationId, attempts, reason);
            events.publishEvent(new ReservationPenaltyAbandonedEvent(reservationId, reason));
            return;
        }

        // Re-armed for another go: claiming cleared the due date, and only a refusal
        // that still has an attempt left puts it back.
        reservation.setPenaltyDueAt(OffsetDateTime.now().plus(RETRY_DELAY));
        reservationRepository.save(reservation);
        log.info("Penalty for reservation {} refused ({}); retrying in {}",
                reservationId, reason, RETRY_DELAY);
    }

    @Transactional(readOnly = true)
    public List<Reservation> cardsToForget(OffsetDateTime now) {
        return reservationRepository.findCardsToDetach(
                now.minus(CARD_RETENTION_AFTER_SERVICE), MAX_ATTEMPTS,
                ChargeKind.NO_SHOW_PENALTY, ChargeStatus.PAID);
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
                || reservation.getNoShowRecordedAt() == null
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
                // Keyed on the recording as well as the attempt. An absence taken back
                // and recorded again is a new debt: without the timestamp, Stripe would
                // replay the first authorisation and nobody would ever be charged.
                "penalty-" + reservation.getId()
                        + "-" + reservation.getNoShowRecordedAt().toEpochSecond()
                        + "-" + reservation.getPenaltyAttempts()));
    }
}
