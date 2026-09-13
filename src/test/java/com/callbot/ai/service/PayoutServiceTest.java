package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.Payout;
import com.callbot.ai.model.PayoutStatus;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.PayoutRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.security.OrganizationScope;

@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock
    private ReservationChargeRepository charges;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private PayoutRepository payoutRepository;
    @Mock
    private OrganizationScope scope;
    @InjectMocks
    private PayoutService service;

    private final UUID restaurantId = UUID.randomUUID();

    private ReservationCharge paid(int amountCents, int feeCents, String currency) {
        return paid(amountCents, feeCents, currency, UUID.randomUUID());
    }

    private ReservationCharge paid(int amountCents, int feeCents, String currency,
            UUID reservationId) {
        return ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.BOOKING_FEE)
                .status(ChargeStatus.PAID)
                .amountCents(amountCents)
                .applicationFeeCents(feeCents)
                .currency(currency)
                .paidAt(OffsetDateTime.now().minusDays(3))
                .payoutEligibleAt(OffsetDateTime.now().minusDays(1))
                .build();
    }

    private void payoutsAreSaved() {
        when(payoutRepository.save(any())).thenAnswer(invocation -> {
            Payout payout = invocation.getArgument(0);
            if (payout.getId() == null) {
                payout.setId(UUID.randomUUID());
            }
            return payout;
        });
    }

    @Test
    void claimsWhatIsLeftAfterTheCommission() {
        payoutsAreSaved();
        List<ReservationCharge> due = List.of(paid(9000, 500, "eur"), paid(3000, 200, "eur"));
        when(charges.lockDuePayoutsFor(eq(restaurantId), eq(ChargeStatus.PAID), any()))
                .thenReturn(due);

        List<Payout> claimed = service.claim(restaurantId, OffsetDateTime.now());

        assertThat(claimed).singleElement().satisfies(payout -> {
            assertThat(payout.getAmountCents()).isEqualTo(8500 + 2800);
            assertThat(payout.getReservationCount()).isEqualTo(2);
            assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PENDING);
        });
        // Claiming is what stops a second instance paying the same fees.
        assertThat(due).allSatisfy(charge -> {
            assertThat(charge.getPaidOutAt()).isNotNull();
            assertThat(charge.getPayoutId()).isNotNull();
        });
    }

    @Test
    void neverMixesCurrenciesIntoOneTransfer() {
        payoutsAreSaved();
        when(charges.lockDuePayoutsFor(eq(restaurantId), eq(ChargeStatus.PAID), any()))
                .thenReturn(List.of(paid(9000, 500, "eur"), paid(2000, 150, "chf")));

        List<Payout> claimed = service.claim(restaurantId, OffsetDateTime.now());

        assertThat(claimed).hasSize(2);
        assertThat(claimed).extracting(Payout::getCurrency).containsExactlyInAnyOrder("eur", "chf");
        assertThat(claimed).extracting(Payout::getAmountCents).containsExactlyInAnyOrder(8500, 1850);
    }

    @Test
    void chargesTakenByAnotherInstanceLeaveNothingToClaim() {
        when(charges.lockDuePayoutsFor(eq(restaurantId), eq(ChargeStatus.PAID), any()))
                .thenReturn(List.of());

        assertThat(service.claim(restaurantId, OffsetDateTime.now())).isEmpty();

        verify(payoutRepository, never()).save(any());
    }

    @Test
    void settlingASuccessRecordsTheStripeTransfer() {
        UUID payoutId = UUID.randomUUID();
        Payout payout = Payout.builder().id(payoutId).amountCents(8500).build();
        when(payoutRepository.findById(payoutId)).thenReturn(Optional.of(payout));

        service.settle(payoutId, "po_1", null);

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.PAID);
        assertThat(payout.getStripePayoutId()).isEqualTo("po_1");
        verify(charges, never()).findByPayoutId(any());
    }

    @Test
    void settlingAFailureHandsTheChargesBackToTheNextSweep() {
        UUID payoutId = UUID.randomUUID();
        Payout payout = Payout.builder().id(payoutId).amountCents(8500).build();
        ReservationCharge claimed = paid(9000, 500, "eur");
        claimed.setPaidOutAt(OffsetDateTime.now());
        claimed.setPayoutId(payoutId);
        when(payoutRepository.findById(payoutId)).thenReturn(Optional.of(payout));
        when(charges.findByPayoutId(payoutId)).thenReturn(List.of(claimed));

        service.settle(payoutId, null, "balance not yet available");

        assertThat(payout.getStatus()).isEqualTo(PayoutStatus.FAILED);
        assertThat(payout.getFailureMessage()).isEqualTo("balance not yet available");
        assertThat(claimed.getPaidOutAt()).isNull();
        assertThat(claimed.getPayoutId()).isNull();
    }

    /** The register's whole point: one reservation may owe the restaurateur twice. */
    @Test
    void twoChargesOnOneReservationBothPayOutButCountAsOneReservation() {
        payoutsAreSaved();
        UUID reservationId = UUID.randomUUID();
        when(charges.lockDuePayoutsFor(eq(restaurantId), eq(ChargeStatus.PAID), any()))
                .thenReturn(List.of(paid(9000, 500, "eur", reservationId),
                        paid(3000, 200, "eur", reservationId)));

        List<Payout> claimed = service.claim(restaurantId, OffsetDateTime.now());

        assertThat(claimed).singleElement().satisfies(payout -> {
            assertThat(payout.getAmountCents()).isEqualTo(8500 + 2800);
            assertThat(payout.getReservationCount()).isEqualTo(1);
        });
    }

    @Test
    void aRestaurantStripeHasNotClearedForPayoutsIsNotPayable() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(
                Restaurant.builder().id(restaurantId).stripeAccountId("acct_1")
                        .stripePayoutsEnabled(false).build()));

        assertThat(service.payableRestaurant(restaurantId)).isEmpty();
    }
}
