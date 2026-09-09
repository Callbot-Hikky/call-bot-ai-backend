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
        // A penalty compensates a lost table; Alloquence takes no share of it.
        assertThat(reservation.getApplicationFeeCents()).isZero();
        // The service is already past, so there is nothing left to wait for.
        assertThat(reservation.getPayoutEligibleAt()).isNotNull();
        assertThat(reservation.getPaidAt()).isNotNull();
        verify(events).publishEvent(new ReservationPenaltyChargedEvent(reservationId));
    }

    @Test
    void aFirstRefusalIsRetriedADayLater() {
        Reservation reservation = awaitingPenalty(0);
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
        Reservation reservation = awaitingPenalty(1);
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
