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
import com.callbot.ai.model.Restaurant;
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
    private final UUID restaurantId = UUID.randomUUID();
    private final UUID payoutId = UUID.randomUUID();

    private void restaurantIsPayable() {
        when(payouts.payableRestaurant(restaurantId)).thenReturn(Optional.of(
                Restaurant.builder().id(restaurantId).stripeAccountId(ACCOUNT)
                        .stripePayoutsEnabled(true).build()));
    }

    private Payout claimed() {
        return Payout.builder().id(payoutId).amountCents(8500).currency("eur").build();
    }

    @Test
    void sendsTheClaimedAmountKeyedOnThePayoutSoARetryCannotSendItTwice() {
        restaurantIsPayable();
        when(payouts.claim(eqRestaurant(), any())).thenReturn(List.of(claimed()));
        when(connect.payOut(anyString(), anyInt(), anyString(), anyString())).thenReturn("po_1");

        assertThat(job.payOut(restaurantId, OffsetDateTime.now())).isEqualTo(1);

        verify(connect).payOut(ACCOUNT, 8500, "eur", payoutId.toString());
        verify(payouts).settle(payoutId, "po_1", null);
    }

    @Test
    void aRefusedTransferIsSettledAsAFailureRatherThanLeftHanging() {
        restaurantIsPayable();
        when(payouts.claim(eqRestaurant(), any())).thenReturn(List.of(claimed()));
        when(connect.payOut(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new PaymentGatewayException("balance not yet available"));

        assertThat(job.payOut(restaurantId, OffsetDateTime.now())).isZero();

        verify(payouts).settle(payoutId, null, "balance not yet available");
    }

    @Test
    void aRestaurantWithNoUsableAccountIsSkippedWithoutClaimingAnything() {
        when(payouts.payableRestaurant(restaurantId)).thenReturn(Optional.empty());

        assertThat(job.payOut(restaurantId, OffsetDateTime.now())).isZero();

        verify(payouts, never()).claim(any(), any());
        verify(connect, never()).payOut(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void oneRestaurantFailingDoesNotStopTheSweep() {
        UUID other = UUID.randomUUID();
        when(payouts.restaurantsWithMoneyDue(any())).thenReturn(List.of(restaurantId, other));
        when(payouts.payableRestaurant(restaurantId)).thenThrow(new IllegalStateException("boom"));
        when(payouts.payableRestaurant(other)).thenReturn(Optional.of(
                Restaurant.builder().id(other).stripeAccountId(ACCOUNT)
                        .stripePayoutsEnabled(true).build()));
        when(payouts.claim(org.mockito.ArgumentMatchers.eq(other), any())).thenReturn(List.of(claimed()));
        when(connect.payOut(anyString(), anyInt(), anyString(), anyString())).thenReturn("po_2");

        job.payOutDue();

        verify(payouts).settle(payoutId, "po_2", null);
    }

    private UUID eqRestaurant() {
        return org.mockito.ArgumentMatchers.eq(restaurantId);
    }
}
