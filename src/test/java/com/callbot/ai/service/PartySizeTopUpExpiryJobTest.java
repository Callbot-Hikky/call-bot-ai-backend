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

import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.notification.ReservationTopUpExpiredEvent;
import com.callbot.ai.repository.ReservationChargeRepository;

@ExtendWith(MockitoExtension.class)
class PartySizeTopUpExpiryJobTest {

    @Mock
    private ReservationChargeRepository charges;
    @Mock
    private PartySizeTopUpService topUps;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private PartySizeTopUpExpiryJob job;

    private final UUID reservationId = UUID.randomUUID();

    private ReservationCharge expiredRequest() {
        return ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.PARTY_SIZE_TOP_UP)
                .status(ChargeStatus.PENDING)
                .amountCents(3000)
                .targetPartySize(5)
                .paymentToken("tok")
                .tokenExpiresAt(OffsetDateTime.now().minusMinutes(1))
                .build();
    }

    private void expired(ReservationCharge... found) {
        when(charges.lockExpiredTopUps(any(), any(), any())).thenReturn(List.of(found));
    }

    @Test
    void endsTheRequestWhoseWindowHasClosed() {
        ReservationCharge charge = expiredRequest();
        expired(charge);

        job.lapseExpiredTopUps();

        verify(topUps).lapse(charge, "the settlement window closed");
    }

    @Test
    void tellsTheDinerAndTheRestaurantTheRequestFellThrough() {
        // The reservation itself never moved, so the news is that nothing happened —
        // which is exactly what someone waiting on a bigger table needs to hear.
        ReservationCharge charge = expiredRequest();
        expired(charge);

        job.lapseExpiredTopUps();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue())
                .isEqualTo(new ReservationTopUpExpiredEvent(reservationId, charge.getId()));
    }

    @Test
    void doesNothingWhenNoWindowHasClosed() {
        expired();

        job.lapseExpiredTopUps();

        verify(topUps, never()).lapse(any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void onlyLooksAtRequestsStillAwaitingSettlement() {
        // A request already settled, refunded or ended has nothing left to expire.
        expired();

        job.lapseExpiredTopUps();

        ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(charges).lockExpiredTopUps(kind.capture(), status.capture(), any());
        assertThat(kind.getValue()).isEqualTo(ChargeKind.PARTY_SIZE_TOP_UP);
        assertThat(status.getValue()).isEqualTo(ChargeStatus.PENDING);
    }
}
