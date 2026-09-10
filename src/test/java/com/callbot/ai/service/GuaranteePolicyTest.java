package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;

class GuaranteePolicyTest {

    private final GuaranteePolicy policy = new GuaranteePolicy();

    private Restaurant restaurant(GuaranteeMode mode, Integer bookingFee, Integer penalty) {
        return Restaurant.builder()
                .id(UUID.randomUUID())
                .guaranteeMode(mode.code())
                .bookingFeeCentsPerGuest(bookingFee)
                .noShowPenaltyCentsPerGuest(penalty)
                .refundWindowHours(48)
                .modificationWindowHours(3)
                .build();
    }

    private Reservation reservation(int partySize) {
        return Reservation.builder().partySize(partySize).build();
    }

    @Test
    void freeRestaurant_leavesTheReservationUntouched() {
        Reservation reservation = reservation(2);

        policy.applyOnCreation(reservation, restaurant(GuaranteeMode.NONE, null, null), null);

        assertThat(reservation.getGuaranteeMode()).isEqualTo("none");
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.NOT_REQUIRED);
        assertThat(reservation.getGuaranteeAmountCents()).isNull();
        assertThat(reservation.getPaymentToken()).isNull();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    void everyReservation_getsAModificationLinkAndTheWindowItWasSoldWith() {
        // Even a free one: changing covers and time is not a privilege of paying tables.
        Reservation reservation = reservation(2);

        policy.applyOnCreation(reservation, restaurant(GuaranteeMode.NONE, null, null), null);

        assertThat(reservation.getModificationToken()).isNotBlank();
        assertThat(reservation.getModificationWindowHours()).isEqualTo(3);
    }

    @Test
    void modificationToken_differsFromTheCancellationToken() {
        // One link must never do the other's job.
        Reservation reservation = reservation(2);

        policy.applyOnCreation(reservation, restaurant(GuaranteeMode.BOOKING_FEE, 1500, null), null);

        assertThat(reservation.getModificationToken())
                .isNotEqualTo(reservation.getCancellationToken())
                .isNotEqualTo(reservation.getPaymentToken());
    }

    @Test
    void modificationWindow_isFrozenEvenWhenTheRestaurantAsksForNothing() {
        // Frozen with the rest: a restaurateur tightening the setting afterwards must not
        // retract a promise already made to a diner.
        Reservation reservation = reservation(2);
        Restaurant restaurant = restaurant(GuaranteeMode.NONE, null, null);
        restaurant.setModificationWindowHours(12);

        policy.applyOnCreation(reservation, restaurant, null);

        assertThat(reservation.getModificationWindowHours()).isEqualTo(12);
    }

    @Test
    void bookingFee_isChargedPerGuest() {
        Reservation reservation = reservation(6);

        policy.applyOnCreation(reservation, restaurant(GuaranteeMode.BOOKING_FEE, 1500, null), null);

        assertThat(reservation.getGuaranteeAmountCents()).isEqualTo(9000);
        assertThat(reservation.getCurrency()).isEqualTo("eur");
    }

    @Test
    void bookingFee_preHoldsTheTableForTheDurationOfThePaymentWindow() {
        Reservation reservation = reservation(2);
        OffsetDateTime before = OffsetDateTime.now();

        policy.applyOnCreation(reservation, restaurant(GuaranteeMode.BOOKING_FEE, 1500, null), null);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.AWAITING_PAYMENT);
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.AWAITING);
        assertThat(reservation.getPaymentToken()).isNotBlank();
        assertThat(reservation.getGuaranteeExpiresAt())
                .isAfter(before.plus(GuaranteePolicy.PAYMENT_WINDOW).minusSeconds(5))
                .isBefore(before.plus(GuaranteePolicy.PAYMENT_WINDOW).plusSeconds(5));
    }

    @Test
    void noShowMode_usesItsOwnAmount_notTheBookingFee() {
        Reservation reservation = reservation(4);

        policy.applyOnCreation(reservation, restaurant(GuaranteeMode.NO_SHOW, 1000, 2500), null);

        assertThat(reservation.getGuaranteeAmountCents()).isEqualTo(10000);
        assertThat(reservation.getGuaranteeMode()).isEqualTo("no_show");
    }

    @Test
    void everyReservationGetsACancellationToken_evenWhenFree() {
        Reservation free = reservation(2);
        Reservation paying = reservation(2);

        policy.applyOnCreation(free, restaurant(GuaranteeMode.NONE, null, null), null);
        policy.applyOnCreation(paying, restaurant(GuaranteeMode.BOOKING_FEE, 1500, null), null);

        assertThat(free.getCancellationToken()).isNotBlank();
        assertThat(paying.getCancellationToken()).isNotBlank();
        assertThat(free.getCancellationToken()).isNotEqualTo(paying.getCancellationToken());
    }

    @Test
    void exemptedByStaff_confirmsWithoutAskingTheDinerForAnything() {
        Reservation reservation = reservation(3);
        UUID staffId = UUID.randomUUID();

        policy.applyOnCreation(reservation, restaurant(GuaranteeMode.BOOKING_FEE, 1500, null), staffId);

        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.EXEMPTED);
        assertThat(reservation.getGuaranteeExemptedBy()).isEqualTo(staffId);
        assertThat(reservation.getPaymentToken()).isNull();
        assertThat(reservation.getGuaranteeExpiresAt()).isNull();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
        // The amount still says what was waived.
        assertThat(reservation.getGuaranteeAmountCents()).isEqualTo(4500);
    }

    @Test
    void payingModeWithoutAPartySize_isRefusedRatherThanChargingForOneGuest() {
        Reservation reservation = Reservation.builder().build();

        assertThatThrownBy(() -> policy.applyOnCreation(
                reservation, restaurant(GuaranteeMode.BOOKING_FEE, 1500, null), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("party size");
    }

    @Test
    void payingModeWithoutAnAmount_isRefusedRatherThanChargingZero() {
        Reservation reservation = reservation(2);

        assertThatThrownBy(() -> policy.applyOnCreation(
                reservation, restaurant(GuaranteeMode.BOOKING_FEE, null, null), null))
                .isInstanceOf(IllegalStateException.class);
    }
}
