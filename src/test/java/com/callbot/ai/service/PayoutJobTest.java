package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Payout;

@ExtendWith(MockitoExtension.class)
class PayoutJobTest {

    @Mock
    private PayoutService payouts;
    @Mock
    private StripeConnectGateway connect;
    @InjectMocks
    private PayoutJob job;

    private static final String ACCOUNT = "acct_123";
    private final UUID organizationId = UUID.randomUUID();
    private final UUID payoutId = UUID.randomUUID();

    private void organizationIsPayable() {
        when(payouts.payableOrganization(organizationId)).thenReturn(Optional.of(
                Organization.builder().id(organizationId).stripeAccountId(ACCOUNT)
                        .stripePayoutsEnabled(true).build()));
    }

    private Payout claimed() {
        return Payout.builder().id(payoutId).amountCents(8500).currency("eur").build();
    }

    @Test
    void sendsTheClaimedAmountKeyedOnThePayoutSoARetryCannotSendItTwice() {
        organizationIsPayable();
        when(payouts.claim(eqOrganization(), any())).thenReturn(List.of(claimed()));
        when(connect.payOut(anyString(), anyInt(), anyString(), anyString())).thenReturn("po_1");

        assertThat(job.payOut(organizationId, OffsetDateTime.now())).isEqualTo(1);

        verify(connect).payOut(ACCOUNT, 8500, "eur", payoutId.toString());
        verify(payouts).settle(payoutId, "po_1", null);
    }

    @Test
    void aRefusedTransferIsSettledAsAFailureRatherThanLeftHanging() {
        organizationIsPayable();
        when(payouts.claim(eqOrganization(), any())).thenReturn(List.of(claimed()));
        when(connect.payOut(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new PaymentGatewayException("balance not yet available"));

        assertThat(job.payOut(organizationId, OffsetDateTime.now())).isZero();

        verify(payouts).settle(payoutId, null, "balance not yet available");
    }

    @Test
    void anOrganizationWithNoUsableAccountIsSkippedWithoutClaimingAnything() {
        when(payouts.payableOrganization(organizationId)).thenReturn(Optional.empty());

        assertThat(job.payOut(organizationId, OffsetDateTime.now())).isZero();

        verify(payouts, never()).claim(any(), any());
        verify(connect, never()).payOut(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void oneOrganizationFailingDoesNotStopTheSweep() {
        UUID other = UUID.randomUUID();
        when(payouts.organizationsWithMoneyDue(any())).thenReturn(List.of(organizationId, other));
        when(payouts.payableOrganization(organizationId)).thenThrow(new IllegalStateException("boom"));
        when(payouts.payableOrganization(other)).thenReturn(Optional.of(
                Organization.builder().id(other).stripeAccountId(ACCOUNT)
                        .stripePayoutsEnabled(true).build()));
        when(payouts.claim(org.mockito.ArgumentMatchers.eq(other), any())).thenReturn(List.of(claimed()));
        when(connect.payOut(anyString(), anyInt(), anyString(), anyString())).thenReturn("po_2");

        job.payOutDue();

        verify(payouts).settle(payoutId, "po_2", null);
    }

    private UUID eqOrganization() {
        return org.mockito.ArgumentMatchers.eq(organizationId);
    }
}
