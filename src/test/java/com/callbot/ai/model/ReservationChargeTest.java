package com.callbot.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class ReservationChargeTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-01-01T12:00:00Z");

    /** 40,00 € collected, of which Alloquence keeps 4,00 €. */
    private ReservationCharge charge() {
        return ReservationCharge.builder()
                .kind(ChargeKind.BOOKING_FEE)
                .status(ChargeStatus.PAID)
                .amountCents(4000)
                .applicationFeeCents(400)
                .payoutEligibleAt(NOW)
                .build();
    }

    @Test
    void restaurateurShare_isTheAmountLessTheCommission() {
        assertThat(charge().restaurateurShareCents()).isEqualTo(3600);
    }

    @Test
    void refundingPartOfIt_leavesTheChargePaidAndPayable() {
        // The covers that remain were still sold: the rest is owed to the restaurateur
        // and must still reach their bank.
        ReservationCharge charge = charge();

        charge.refundPartially(NOW, 1000);

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.getRefundedAmountCents()).isEqualTo(1000);
        assertThat(charge.getPayoutEligibleAt()).isEqualTo(NOW);
    }

    @Test
    void aPartialRefund_shrinksWhatIsOwedInProportion() {
        // The commission goes back with the money, so the share owed falls by more than
        // the refund alone: a quarter of the table came back, so a quarter of the share.
        ReservationCharge charge = charge();

        charge.refundPartially(NOW, 1000);

        assertThat(charge.restaurateurShareCents()).isEqualTo(2700);
    }

    @Test
    void successiveRefunds_accumulate() {
        // A party that falls twice hands back twice, on the one charge that paid for it.
        ReservationCharge charge = charge();

        charge.refundPartially(NOW, 1000);
        charge.refundPartially(NOW, 1000);

        assertThat(charge.getRefundedAmountCents()).isEqualTo(2000);
        assertThat(charge.restaurateurShareCents()).isEqualTo(1800);
    }

    @Test
    void refundingTheWholeOfIt_closesTheChargeAndStopsThePayout() {
        // Nothing is left to send: leaving it payable would pay a restaurateur money
        // that has already gone back.
        ReservationCharge charge = charge();

        charge.refundPartially(NOW, 4000);

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(charge.getPayoutEligibleAt()).isNull();
        assertThat(charge.restaurateurShareCents()).isZero();
    }

    @Test
    void refundingMoreThanIsLeft_isRefused() {
        // Silently capping would hand back money that never came in and leave the
        // register claiming a movement Stripe never made.
        ReservationCharge charge = charge();
        charge.refundPartially(NOW, 3000);

        assertThatThrownBy(() -> charge.refundPartially(NOW, 1500))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundingNothingOrLess_isRefused() {
        assertThatThrownBy(() -> charge().refundPartially(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
