package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

@ExtendWith(MockitoExtension.class)
class PartySizeChangePolicyTest {

    @Mock
    private RestaurantTableRepository tableRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @InjectMocks
    private PartySizeChangePolicy policy;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID ownTableId = UUID.randomUUID();

    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2030-01-01T19:00:00Z");
    private static final OffsetDateTime ENDS_AT = OffsetDateTime.parse("2030-01-01T21:00:00Z");

    private Reservation reservation(String mode) {
        return Reservation.builder()
                .id(reservationId)
                .restaurantId(restaurantId)
                .tableId(ownTableId)
                .partySize(2)
                .guaranteeMode(mode)
                .build();
    }

    private RestaurantTable table(UUID id, int capacity) {
        return RestaurantTable.builder()
                .id(id)
                .restaurantId(restaurantId)
                .capacity(capacity)
                .isActive(true)
                .build();
    }

    private void tables(RestaurantTable... tables) {
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(tables));
    }

    private void busy(UUID... tableIds) {
        when(reservationRepository.findBusyTableIdsExcluding(restaurantId, STARTS_AT, ENDS_AT, reservationId))
                .thenReturn(List.of(tableIds));
    }

    private void check(Reservation reservation, int newPartySize) {
        policy.check(reservation, newPartySize, STARTS_AT, ENDS_AT);
    }

    @Test
    void check_whenPartySizeGoesDown_passesWithoutLookingAtTablesOrMoney() {
        // Paying mode included: a partial refund does not exist, so shrinking gives nothing back.
        assertThatCode(() -> check(reservation(GuaranteeMode.BOOKING_FEE.code()), 1))
                .doesNotThrowAnyException();

        verify(tableRepository, never()).findByRestaurantId(restaurantId);
    }

    @Test
    void check_whenPartySizeIsUnchanged_passes() {
        assertThatCode(() -> check(reservation(GuaranteeMode.BOOKING_FEE.code()), 2))
                .doesNotThrowAnyException();

        verify(tableRepository, never()).findByRestaurantId(restaurantId);
    }

    @Test
    void check_whenNoTableIsLargeEnough_rejectsWithNoTableAvailable() {
        tables(table(ownTableId, 2), table(UUID.randomUUID(), 4));

        assertThatThrownBy(() -> check(reservation(GuaranteeMode.NONE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.NO_TABLE_AVAILABLE);
    }

    @Test
    void check_whenEveryLargeEnoughTableIsTaken_rejectsWithNoTableAvailable() {
        UUID bigTableId = UUID.randomUUID();
        tables(table(ownTableId, 2), table(bigTableId, 6));
        busy(bigTableId);

        assertThatThrownBy(() -> check(reservation(GuaranteeMode.NONE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.NO_TABLE_AVAILABLE);
    }

    @Test
    void check_whenLargeEnoughTableIsInactive_rejectsWithNoTableAvailable() {
        RestaurantTable retired = table(UUID.randomUUID(), 6);
        retired.setIsActive(false);
        tables(table(ownTableId, 2), retired);

        assertThatThrownBy(() -> check(reservation(GuaranteeMode.NONE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.NO_TABLE_AVAILABLE);
    }

    @Test
    void check_whenOwnTableIsAlreadyLargeEnough_passes() {
        // The reservation does not occupy its own table as far as this check is concerned.
        tables(table(ownTableId, 8));
        busy();

        assertThatCode(() -> check(reservation(GuaranteeMode.NONE.code()), 6))
                .doesNotThrowAnyException();
    }

    @Test
    void check_whenRisingInNoneModeWithAFreeTable_passes() {
        tables(table(ownTableId, 2), table(UUID.randomUUID(), 8));
        busy();

        assertThatCode(() -> check(reservation(GuaranteeMode.NONE.code()), 6))
                .doesNotThrowAnyException();
    }

    @Test
    void check_whenRisingInNoShowModeWithAFreeTable_passes() {
        // The penalty is per guest and only taken on an absence: its basis follows the covers.
        tables(table(ownTableId, 2), table(UUID.randomUUID(), 8));
        busy();

        assertThatCode(() -> check(reservation(GuaranteeMode.NO_SHOW.code()), 6))
                .doesNotThrowAnyException();
    }

    @Test
    void check_whenRisingInBookingFeeModeWithAFreeTable_rejectsWithTopUpRequired() {
        tables(table(ownTableId, 2), table(UUID.randomUUID(), 8));
        busy();

        assertThatThrownBy(() -> check(reservation(GuaranteeMode.BOOKING_FEE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.TOP_UP_REQUIRED);
    }

    @Test
    void check_whenNoTableAndBookingFee_reportsTheTableFirst() {
        // Ordering matters: sending staff to collect a top-up for a table that cannot
        // seat the party would ask a diner to pay for something impossible.
        tables(table(ownTableId, 2));

        assertThatThrownBy(() -> check(reservation(GuaranteeMode.BOOKING_FEE.code()), 6))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.NO_TABLE_AVAILABLE);
    }
}
