package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.notification.ReservationGuaranteeExpiredEvent;
import com.callbot.ai.repository.ReservationRepository;

@ExtendWith(MockitoExtension.class)
class GuaranteeExpiryJobTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private GuaranteeExpiryJob job;

    private Reservation expiredHold() {
        return Reservation.builder()
                .id(UUID.randomUUID())
                .status(ReservationStatus.AWAITING_PAYMENT)
                .guaranteeStatus(GuaranteeStatus.AWAITING)
                .paymentToken("a-token")
                .guaranteeExpiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
    }

    @Test
    void releasesTheTableAndKillsThePaymentLink() {
        Reservation reservation = expiredHold();
        when(reservationRepository.lockExpiredHolds(
                any(), any())).thenReturn(List.of(reservation));

        job.releaseExpiredHolds();

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.EXPIRED);
        assertThat(reservation.getCancelledAt()).isNotNull();
        // Paying now would buy a table that has gone back on sale.
        assertThat(reservation.getPaymentToken()).isNull();
        verify(reservationRepository).save(reservation);
    }

    @Test
    void tellsTheDinerTheirTableIsGone() {
        Reservation reservation = expiredHold();
        when(reservationRepository.lockExpiredHolds(
                any(), any())).thenReturn(List.of(reservation));

        job.releaseExpiredHolds();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue())
                .isEqualTo(new ReservationGuaranteeExpiredEvent(reservation.getId()));
    }

    @Test
    void doesNothingWhenNoHoldHasExpired() {
        when(reservationRepository.lockExpiredHolds(
                any(), any())).thenReturn(List.of());

        job.releaseExpiredHolds();

        verify(reservationRepository, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void onlyLooksAtPreHeldReservations() {
        when(reservationRepository.lockExpiredHolds(
                any(), any())).thenReturn(List.of());

        job.releaseExpiredHolds();

        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(reservationRepository).lockExpiredHolds(
                status.capture(), any());
        assertThat(status.getValue()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
    }
}
