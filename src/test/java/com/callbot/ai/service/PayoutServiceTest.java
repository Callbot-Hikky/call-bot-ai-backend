package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Payout;
import com.callbot.ai.model.PayoutStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.PayoutRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.security.CallerOrganizationResolver;

@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private PayoutRepository payoutRepository;
    @Mock
    private StripeConnectGateway connect;
    @Mock
    private CallerOrganizationResolver callerOrganization;
    @InjectMocks
    private PayoutService service;

    private static final String ACCOUNT = "acct_123";
    private final UUID organizationId = UUID.randomUUID();

    private Reservation paid(int amountCents, int feeCents) {
        return Reservation.builder()
                .id(UUID.randomUUID())
                .guaranteeAmountCents(amountCents)
                .applicationFeeCents(feeCents)
                .currency("eur")
                .paidAt(OffsetDateTime.now().minusDays(3))
                .payoutEligibleAt(OffsetDateTime.now().minusDays(1))
                .build();
    }

    private void organizationCanReceiveMoney() {
        when(reservationRepository.findOrganizationsWithDuePayouts(any())).thenReturn(List.of(organizationId));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(
                Organization.builder().id(organizationId).stripeAccountId(ACCOUNT)
                        .stripePayoutsEnabled(true).build()));
        when(payoutRepository.save(any())).thenAnswer(invocation -> {
            Payout payout = invocation.getArgument(0);
            if (payout.getId() == null) {
                payout.setId(UUID.randomUUID());
            }
            return payout;
        });
    }

    @Test
    void paysOutWhatIsLeftAfterTheCommission() {
        organizationCanReceiveMoney();
        List<Reservation> due = List.of(paid(9000, 500), paid(3000, 200));
        when(reservationRepository.lockDuePayoutsFor(eq(organizationId), any())).thenReturn(due);
        when(connect.payOut(anyString(), anyInt(), anyString())).thenReturn("po_1");

        assertThat(service.payOutEverythingDue()).isEqualTo(1);

        verify(connect).payOut(ACCOUNT, 8500 + 2800, "eur");
        assertThat(due).allSatisfy(reservation -> {
            assertThat(reservation.getPaidOutAt()).isNotNull();
            assertThat(reservation.getPayoutId()).isNotNull();
        });
    }

    @Test
    void recordsTheLedgerLineTheRestaurateurReads() {
        organizationCanReceiveMoney();
        when(reservationRepository.lockDuePayoutsFor(eq(organizationId), any()))
                .thenReturn(List.of(paid(9000, 500)));
        when(connect.payOut(anyString(), anyInt(), anyString())).thenReturn("po_1");

        service.payOutEverythingDue();

        ArgumentCaptor<Payout> payout = ArgumentCaptor.forClass(Payout.class);
        verify(payoutRepository, org.mockito.Mockito.atLeastOnce()).save(payout.capture());
        Payout saved = payout.getValue();
        assertThat(saved.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(saved.getStripePayoutId()).isEqualTo("po_1");
        assertThat(saved.getAmountCents()).isEqualTo(8500);
        assertThat(saved.getReservationCount()).isEqualTo(1);
    }

    @Test
    void aRefusedPayoutLeavesTheMoneyDueSoTheNextRunTriesAgain() {
        organizationCanReceiveMoney();
        List<Reservation> due = List.of(paid(9000, 500));
        when(reservationRepository.lockDuePayoutsFor(eq(organizationId), any())).thenReturn(due);
        when(connect.payOut(anyString(), anyInt(), anyString()))
                .thenThrow(new PaymentGatewayException("balance not yet available"));

        assertThat(service.payOutEverythingDue()).isZero();

        assertThat(due.get(0).getPaidOutAt()).isNull();
        verify(reservationRepository, never()).saveAll(any());
    }

    @Test
    void anOrganizationStripeHasNotClearedForPayoutsIsSkipped() {
        when(reservationRepository.findOrganizationsWithDuePayouts(any())).thenReturn(List.of(organizationId));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(
                Organization.builder().id(organizationId).stripeAccountId(ACCOUNT)
                        .stripePayoutsEnabled(false).build()));

        assertThat(service.payOutEverythingDue()).isZero();

        verify(connect, never()).payOut(anyString(), anyInt(), anyString());
    }

    @Test
    void reservationsTakenByAnotherInstanceBetweenTheTwoQueriesAreNotPaidTwice() {
        when(reservationRepository.findOrganizationsWithDuePayouts(any())).thenReturn(List.of(organizationId));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(
                Organization.builder().id(organizationId).stripeAccountId(ACCOUNT)
                        .stripePayoutsEnabled(true).build()));
        when(reservationRepository.lockDuePayoutsFor(eq(organizationId), any())).thenReturn(List.of());

        assertThat(service.payOutEverythingDue()).isZero();

        verify(payoutRepository, never()).save(any());
        verify(connect, never()).payOut(anyString(), anyInt(), anyString());
    }
}
