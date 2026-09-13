package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.repository.ReservationChargeRepository;

@ExtendWith(MockitoExtension.class)
class PartySizeChangePolicyTest {

    @Mock
    private TableAvailability availability;
    @Mock
    private ReservationChargeRepository charges;
    @InjectMocks
    private PartySizeChangePolicy policy;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID ownTableId = UUID.randomUUID();

    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2030-01-01T19:00:00Z");
    private static final OffsetDateTime ENDS_AT = OffsetDateTime.parse("2030-01-01T21:00:00Z");

    private Reservation reservation(String mode) {
        // A booking-fee reservation that was actually paid for: the case where extra
        // guests genuinely owe a top-up.
        return reservation(mode, GuaranteeStatus.SECURED);
    }

    private Reservation reservation(String mode, String guaranteeStatus) {
        return Reservation.builder()
                .id(reservationId)
                .restaurantId(restaurantId)
                .tableId(ownTableId)
                .partySize(2)
                .guaranteeMode(mode)
                .guaranteeStatus(guaranteeStatus)
                .build();
    }

    /** Whether the room can hold the larger party. Table geometry is tested next door. */
    private void roomFor(boolean seatable) {
        when(availability.canSeat(restaurantId, 6, STARTS_AT, ENDS_AT, reservationId))
                .thenReturn(seatable);
    }

    private PartySizeChange decide(Reservation reservation, int newPartySize) {
        return policy.decide(reservation, newPartySize, STARTS_AT, ENDS_AT);
    }

    private void topUpRunning(boolean pending) {
        when(charges.existsByReservationIdAndKindAndStatus(
                reservationId, ChargeKind.PARTY_SIZE_TOP_UP, ChargeStatus.PENDING))
                .thenReturn(pending);
    }

    @Test
    void decide_whenPartySizeGoesDown_passesWithoutLookingAtTablesOrMoney() {
        // Paying mode included: a partial refund does not exist, so shrinking gives nothing back.
        topUpRunning(false);

        assertThat(decide(reservation(GuaranteeMode.BOOKING_FEE.code()), 1))
                .isEqualTo(PartySizeChange.APPLY);

        verify(availability, never()).canSeat(any(), anyInt(), any(), any(), any());
    }

    @Test
    void decide_whenPartySizeGoesDownUnderARunningTopUp_dropsTheRequestWithIt() {
        // The request was priced against the party as it stood. Moving that party leaves
        // a link asking for money that no longer buys what it says it buys.
        topUpRunning(true);

        assertThat(decide(reservation(GuaranteeMode.BOOKING_FEE.code()), 1))
                .isEqualTo(PartySizeChange.APPLY_AND_LAPSE_TOP_UP);
    }

    @Test
    void decide_whenPartySizeIsUnchanged_passesWithoutConsultingAnything() {
        // Nothing moved, so nothing a running request was priced against moved either:
        // the staff member saving a note must not knock over a link the diner is on.
        assertThat(decide(reservation(GuaranteeMode.BOOKING_FEE.code()), 2))
                .isEqualTo(PartySizeChange.APPLY);

        verify(availability, never()).canSeat(any(), anyInt(), any(), any(), any());
        verify(charges, never()).existsByReservationIdAndKindAndStatus(any(), any(), any());
    }

    @Test
    void decide_whenNothingCanSeatTheParty_rejectsWithNoTableAvailable() {
        roomFor(false);

        assertThatThrownBy(() -> decide(reservation(GuaranteeMode.NONE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.NO_TABLE_AVAILABLE);
    }

    @Test
    void decide_whenRisingInNoneModeWithAFreeTable_passes() {
        roomFor(true);

        assertThat(decide(reservation(GuaranteeMode.NONE.code()), 6))
                .isEqualTo(PartySizeChange.APPLY);
    }

    @Test
    void decide_whenRisingInNoShowModeWithAFreeTable_passes() {
        // The penalty is per guest and only taken on an absence: its basis follows the covers.
        roomFor(true);

        assertThat(decide(reservation(GuaranteeMode.NO_SHOW.code()), 6))
                .isEqualTo(PartySizeChange.APPLY);
    }

    @Test
    void decide_whenRisingInBookingFeeModeWithAFreeTable_asksForATopUp() {
        // The fee was priced per guest, so the guests added are owed before the rise counts.
        roomFor(true);
        topUpRunning(false);

        assertThat(decide(reservation(GuaranteeMode.BOOKING_FEE.code()), 6))
                .isEqualTo(PartySizeChange.COLLECT_TOP_UP);
    }

    @Test
    void decide_whenATopUpIsAlreadyRunning_rejectsRatherThanOpeningASecond() {
        // Two live links would each be payable, for one table.
        topUpRunning(true);

        assertThatThrownBy(() -> decide(reservation(GuaranteeMode.BOOKING_FEE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.TOP_UP_PENDING);

        // Turned away before the room is even looked at: the answer does not depend on it.
        verify(availability, never()).canSeat(any(), anyInt(), any(), any(), any());
    }

    @Test
    void decide_whenNoTableAndBookingFee_reportsTheTableFirst() {
        // Ordering matters: sending a diner a payment link for a table that cannot seat
        // the party would ask them to pay for something impossible.
        roomFor(false);
        topUpRunning(false);

        assertThatThrownBy(() -> decide(reservation(GuaranteeMode.BOOKING_FEE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.NO_TABLE_AVAILABLE);
    }

    @Test
    void decide_whenRisingInBookingFeeModeAwaitingPayment_asksForATopUp() {
        // The amount was frozen when the link was sent: growing the party underneath it
        // would quietly undercharge the table.
        roomFor(true);
        topUpRunning(false);

        assertThat(decide(
                reservation(GuaranteeMode.BOOKING_FEE.code(), GuaranteeStatus.AWAITING), 6))
                .isEqualTo(PartySizeChange.COLLECT_TOP_UP);
    }

    @Test
    void decide_whenRisingInBookingFeeModeButStaffWaivedTheFee_passes() {
        // Nothing was ever collected: asking for a top-up would strand the party behind
        // a payment of zero.
        roomFor(true);
        topUpRunning(false);

        assertThat(decide(
                reservation(GuaranteeMode.BOOKING_FEE.code(), GuaranteeStatus.EXEMPTED), 6))
                .isEqualTo(PartySizeChange.APPLY);
    }

    @Test
    void decide_whenRisingInBookingFeeModeButTheFeeWasRefunded_passes() {
        roomFor(true);
        topUpRunning(false);

        assertThat(decide(
                reservation(GuaranteeMode.BOOKING_FEE.code(), GuaranteeStatus.REFUNDED), 6))
                .isEqualTo(PartySizeChange.APPLY);
    }

    @Test
    void decide_whenRisingInBookingFeeModeButNoFeeWasAsked_passes() {
        roomFor(true);
        topUpRunning(false);

        assertThat(decide(
                reservation(GuaranteeMode.BOOKING_FEE.code(), GuaranteeStatus.NOT_REQUIRED), 6))
                .isEqualTo(PartySizeChange.APPLY);
    }

    @Test
    void decide_whenWaivedFeeButNoTableIsLargeEnough_stillRejectsOnTheTable() {
        // Waiving the fee frees the money question, not the room.
        roomFor(false);
        topUpRunning(false);

        assertThatThrownBy(() -> decide(
                reservation(GuaranteeMode.BOOKING_FEE.code(), GuaranteeStatus.EXEMPTED), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.NO_TABLE_AVAILABLE);
    }
}
