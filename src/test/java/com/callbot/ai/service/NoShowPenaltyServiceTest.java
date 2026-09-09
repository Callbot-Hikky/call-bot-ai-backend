package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.notification.ReservationPenaltyAbandonedEvent;
import com.callbot.ai.notification.ReservationPenaltyChargedEvent;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class NoShowPenaltyServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private NoShowPenaltyService service;

    private final UUID reservationId = UUID.randomUUID();

    private Reservation awaitingPenalty(int attempts) {
        return Reservation.builder()
                .id(reservationId)
                .restaurantId(UUID.randomUUID())
                .status(ReservationStatus.NO_SHOW)
                .endsAt(OffsetDateTime.now().minusHours(3))
                .noShowRecordedAt(OffsetDateTime.now().minusHours(2))
                .guaranteeStatus(GuaranteeStatus.SECURED)
                .guaranteeAmountCents(10000)
                .currency("eur")
                .stripeCustomerId("cus_1")
                .stripePaymentMethodId("pm_1")
                .penaltyDueAt(OffsetDateTime.now().minusMinutes(1))
                .penaltyAttempts(attempts)
                .build();
    }

    private void reservationExists(Reservation reservation) {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void aChargedPenaltyOwesAlloquenceNothingAndIsPayableAtOnce() {
        Reservation reservation = awaitingPenalty(0);
        reservationExists(reservation);

        service.settleCharged(reservationId, "pi_1");

        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.CHARGED);
        assertThat(reservation.getPenaltyChargedAt()).isNotNull();
        assertThat(reservation.getPenaltyDueAt()).isNull();
        // Counted once, at claim time — never twice.
        assertThat(reservation.getPenaltyAttempts()).isZero();
        // A penalty compensates a lost table; Alloquence takes no share of it.
        assertThat(reservation.getApplicationFeeCents()).isZero();
        // Same payout rule as a booking fee: a day after the service, not at once.
        assertThat(reservation.getPayoutEligibleAt()).isEqualTo(reservation.getEndsAt().plusDays(1));
        // Kept apart from the booking-fee intent, which is what bank disputes look up.
        assertThat(reservation.getStripePenaltyIntentId()).isEqualTo("pi_1");
        assertThat(reservation.getStripePaymentIntentId()).isNull();
        verify(events).publishEvent(new ReservationPenaltyChargedEvent(reservationId));
    }

    @Test
    void claimingCommitsTheOwnershipSoNoOtherInstanceCanDebitTheSameCard() {
        Reservation reservation = awaitingPenalty(0);
        when(reservationRepository.lockDuePenalties(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.List.of(reservation));
        when(reservationRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        service.claimDuePenalties(OffsetDateTime.now());

        // The row lock dies with this transaction and the Stripe call comes after it;
        // only the write makes the claim exclusive.
        assertThat(reservation.getPenaltyAttempts()).isEqualTo(1);
        assertThat(reservation.getPenaltyDueAt()).isNull();
    }

    @Test
    void aFirstRefusalIsRetriedADayLater() {
        // The claim already counted this attempt.
        Reservation reservation = awaitingPenalty(1);
        reservation.setPenaltyDueAt(null);
        reservationExists(reservation);
        OffsetDateTime before = OffsetDateTime.now();

        service.settleFailed(reservationId, "card_declined");

        assertThat(reservation.getPenaltyAttempts()).isEqualTo(1);
        assertThat(reservation.getPenaltyDueAt())
                .isAfter(before.plus(NoShowPenaltyService.RETRY_DELAY).minusMinutes(1));
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.SECURED);
        verify(events, never()).publishEvent(any(ReservationPenaltyAbandonedEvent.class));
    }

    @Test
    void aSecondRefusalGivesUpForGoodAndTellsTheRestaurateur() {
        Reservation reservation = awaitingPenalty(NoShowPenaltyService.MAX_ATTEMPTS);
        reservationExists(reservation);

        service.settleFailed(reservationId, "insufficient_funds");

        assertThat(reservation.getPenaltyAttempts()).isEqualTo(NoShowPenaltyService.MAX_ATTEMPTS);
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.CHARGE_FAILED);
        // Nothing is scheduled: a card refused twice a day apart will not say yes.
        assertThat(reservation.getPenaltyDueAt()).isNull();
        verify(events).publishEvent(
                new ReservationPenaltyAbandonedEvent(reservationId, "insufficient_funds"));
    }

    @Test
    void eachAttemptCarriesItsOwnIdempotencyKeySoARetryIsNotBlockedByTheLastRefusal() {
        Reservation first = awaitingPenalty(0);
        Reservation second = awaitingPenalty(1);

        String firstKey = service.chargeFor(first, "acct_1").orElseThrow().idempotencyKey();
        String secondKey = service.chargeFor(second, "acct_1").orElseThrow().idempotencyKey();

        assertThat(firstKey).isNotEqualTo(secondKey);
        assertThat(firstKey).contains(reservationId.toString());
    }

    @Test
    void aChargeThatLandsAfterTheAbsenceWasRetractedIsNotWrittenBack() {
        Reservation reservation = awaitingPenalty(1);
        reservation.setStatus(ReservationStatus.CONFIRMED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        service.settleCharged(reservationId, "pi_1");

        // The money left, but the system must not restate that the diner was absent.
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.SECURED);
        assertThat(reservation.getPenaltyChargedAt()).isNull();
        verify(events, never()).publishEvent(any(ReservationPenaltyChargedEvent.class));
    }

    @Test
    void anAbsenceRecordedAgainAfterBeingRetractedGetsItsOwnIdempotencyKey() {
        Reservation first = awaitingPenalty(1);
        Reservation reRecorded = awaitingPenalty(1);
        reRecorded.setNoShowRecordedAt(first.getNoShowRecordedAt().plusMinutes(30));

        // Without the recording in the key, Stripe would replay the first authorisation
        // and the second, genuine debt would never be taken.
        assertThat(service.chargeFor(first, "acct_1").orElseThrow().idempotencyKey())
                .isNotEqualTo(service.chargeFor(reRecorded, "acct_1").orElseThrow().idempotencyKey());
    }

    @Test
    void nothingToChargeIsGivenUpOnAtOnceRatherThanRetriedForADay() {
        Reservation reservation = awaitingPenalty(1);
        reservationExists(reservation);

        service.abandon(reservationId, "Aucun moyen de paiement exploitable");

        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.CHARGE_FAILED);
        assertThat(reservation.getPenaltyDueAt()).isNull();
        verify(events).publishEvent(new ReservationPenaltyAbandonedEvent(
                reservationId, "Aucun moyen de paiement exploitable"));
    }

    @Test
    void aReservationWithoutAUsableCardProducesNoCharge() {
        Reservation reservation = awaitingPenalty(0);
        reservation.setStripePaymentMethodId(null);

        assertThat(service.chargeFor(reservation, "acct_1")).isEmpty();
        assertThat(service.chargeFor(awaitingPenalty(0), null)).isEmpty();
    }

    @Test
    void forgettingACardClearsItFromTheRowAsWellAsFromStripe() {
        Reservation reservation = awaitingPenalty(0);
        reservationExists(reservation);

        service.markCardForgotten(reservationId);

        assertThat(reservation.getStripePaymentMethodId()).isNull();
        assertThat(reservation.getPaymentMethodDetachedAt()).isNotNull();
    }
}
