package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.security.OrganizationScope;

@ExtendWith(MockitoExtension.class)
class NoShowServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrganizationScope scope;
    @InjectMocks
    private NoShowService service;

    private static final String STAFF = "serveur@resto.fr";
    private final UUID reservationId = UUID.randomUUID();
    private final UUID staffId = UUID.randomUUID();

    private Reservation servedAndGuaranteed() {
        return Reservation.builder()
                .id(reservationId)
                .restaurantId(UUID.randomUUID())
                .partySize(4)
                .startsAt(OffsetDateTime.now().minusHours(3))
                .endsAt(OffsetDateTime.now().minusHours(1))
                .status(ReservationStatus.CONFIRMED)
                .guaranteeMode(GuaranteeMode.NO_SHOW.code())
                .guaranteeStatus(GuaranteeStatus.SECURED)
                .guaranteeAmountCents(10000)
                .stripePaymentMethodId("pm_1")
                .stripeCustomerId("cus_1")
                .currency("eur")
                .build();
    }

    private void reservationExists(Reservation reservation) {
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void recordingAnAbsenceDoesNotDebitAnything_itOnlyStartsTheClock() {
        Reservation reservation = servedAndGuaranteed();
        reservationExists(reservation);
        when(scope.userIdOf(STAFF)).thenReturn(Optional.of(staffId));
        OffsetDateTime before = OffsetDateTime.now();

        service.record(reservationId, STAFF);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.getNoShowRecordedBy()).isEqualTo(staffId);
        assertThat(reservation.getPenaltyChargedAt()).isNull();
        assertThat(reservation.getPenaltyDueAt())
                .isAfter(before.plus(NoShowService.CANCELLATION_WINDOW).minusMinutes(1));
    }

    @Test
    void anAbsenceRecordedByNobodyIsRefused() {
        Reservation reservation = servedAndGuaranteed();
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        // The AI microservice: an API key, no person behind it.
        when(scope.userIdOf(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(reservationId, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void aFreeReservationCanBeMarkedAbsentWithoutAnyPenaltyBecomingDue() {
        Reservation reservation = servedAndGuaranteed();
        reservation.setGuaranteeMode(GuaranteeMode.NONE.code());
        reservation.setGuaranteeStatus(GuaranteeStatus.NOT_REQUIRED);
        reservation.setStripePaymentMethodId(null);
        reservationExists(reservation);
        when(scope.userIdOf(STAFF)).thenReturn(Optional.of(staffId));

        service.record(reservationId, STAFF);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
        assertThat(reservation.getPenaltyDueAt()).isNull();
    }

    @Test
    void nobodyIsAbsentFromAMealThatHasNotStarted() {
        Reservation reservation = servedAndGuaranteed();
        reservation.setStartsAt(OffsetDateTime.now().plusHours(2));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(scope.userIdOf(STAFF)).thenReturn(Optional.of(staffId));

        assertThatThrownBy(() -> service.record(reservationId, STAFF))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("service");
    }

    @Test
    void aCancelledReservationCannotBeMarkedAbsent() {
        Reservation reservation = servedAndGuaranteed();
        reservation.setStatus(ReservationStatus.CANCELLED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(scope.userIdOf(STAFF)).thenReturn(Optional.of(staffId));

        assertThatThrownBy(() -> service.record(reservationId, STAFF))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void takingTheAbsenceBackInTimeCancelsTheDebtEntirely() {
        Reservation reservation = servedAndGuaranteed();
        reservation.setStatus(ReservationStatus.NO_SHOW);
        reservation.setNoShowRecordedAt(OffsetDateTime.now().minusMinutes(10));
        reservation.setNoShowRecordedBy(staffId);
        reservation.setPenaltyDueAt(OffsetDateTime.now().plusHours(1));
        reservationExists(reservation);

        service.undo(reservationId, STAFF);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMPLETED);
        assertThat(reservation.getPenaltyDueAt()).isNull();
        assertThat(reservation.getNoShowRecordedAt()).isNull();
    }

    @Test
    void anAbsenceAlreadyPaidForCannotBeQuietlyUndone() {
        Reservation reservation = servedAndGuaranteed();
        reservation.setStatus(ReservationStatus.NO_SHOW);
        reservation.setNoShowRecordedAt(OffsetDateTime.now().minusHours(4));
        reservation.setNoShowRecordedBy(staffId);
        reservation.setPenaltyChargedAt(OffsetDateTime.now().minusHours(1));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // Undoing would leave the diner charged for a reservation the system says they honoured.
        assertThatThrownBy(() -> service.undo(reservationId, STAFF))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("remboursement");
    }

    @Test
    void anAbsenceCannotBeRecordedTwice() {
        Reservation reservation = servedAndGuaranteed();
        reservation.setNoShowRecordedAt(OffsetDateTime.now().minusMinutes(5));
        reservation.setNoShowRecordedBy(staffId);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(scope.userIdOf(STAFF)).thenReturn(Optional.of(staffId));

        assertThatThrownBy(() -> service.record(reservationId, STAFF))
                .isInstanceOf(InvalidRequestException.class);
    }
}
