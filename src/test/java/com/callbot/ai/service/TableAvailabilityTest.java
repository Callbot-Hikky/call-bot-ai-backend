package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

@ExtendWith(MockitoExtension.class)
class TableAvailabilityTest {

    @Mock
    private RestaurantTableRepository tableRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @InjectMocks
    private TableAvailability availability;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2030-01-01T19:00:00Z");
    private static final OffsetDateTime ENDS_AT = OffsetDateTime.parse("2030-01-01T21:00:00Z");

    private RestaurantTable table(UUID id, int capacity, boolean active) {
        return RestaurantTable.builder()
                .id(id)
                .restaurantId(restaurantId)
                .capacity(capacity)
                .isActive(active)
                .build();
    }

    private void tables(RestaurantTable... tables) {
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(tables));
    }

    private void busy(UUID... tableIds) {
        when(reservationRepository.findBusyTableIdsExcluding(
                eq(restaurantId), any(), any(), eq(reservationId))).thenReturn(List.of(tableIds));
    }

    private RestaurantTable firstSeatingFor(int partySize) {
        return availability.firstSeating(restaurantId, partySize, STARTS_AT, ENDS_AT, reservationId);
    }

    @Test
    void firstSeating_returnsTheTableItFound() {
        // The caller needs the table itself, not merely a yes: it is where the party
        // will be moved once the money for the extra guests is in.
        UUID bigEnough = UUID.randomUUID();
        tables(table(UUID.randomUUID(), 2, true), table(bigEnough, 6, true));
        busy();

        assertThat(firstSeatingFor(6)).isNotNull()
                .extracting(RestaurantTable::getId).isEqualTo(bigEnough);
    }

    @Test
    void firstSeating_ignoresTablesTooSmallForTheParty() {
        tables(table(UUID.randomUUID(), 2, true), table(UUID.randomUUID(), 4, true));

        assertThat(firstSeatingFor(6)).isNull();
        // Not one occupancy query is worth running once nothing could seat them anyway.
        verify(reservationRepository, never()).findBusyTableIdsExcluding(any(), any(), any(), any());
    }

    @Test
    void firstSeating_ignoresTablesOutOfService() {
        tables(table(UUID.randomUUID(), 8, false));

        assertThat(firstSeatingFor(6)).isNull();
    }

    @Test
    void firstSeating_ignoresTablesTakenOverTheSlot() {
        UUID taken = UUID.randomUUID();
        tables(table(taken, 6, true));
        busy(taken);

        assertThat(firstSeatingFor(6)).isNull();
    }

    @Test
    void firstSeating_doesNotCountTheReservationAgainstItself() {
        // Its own table is excluded from the occupancy query, so a table already large
        // enough stays a candidate and the party simply stays where it sits.
        UUID ownTable = UUID.randomUUID();
        tables(table(ownTable, 6, true));
        busy();

        assertThat(firstSeatingFor(6)).isNotNull()
                .extracting(RestaurantTable::getId).isEqualTo(ownTable);
        verify(reservationRepository).findBusyTableIdsExcluding(
                restaurantId, STARTS_AT, ENDS_AT, reservationId);
    }

    @Test
    void canSeat_answersTheSameQuestionWithoutTheTable() {
        tables(table(UUID.randomUUID(), 6, true));
        busy();

        assertThat(availability.canSeat(restaurantId, 6, STARTS_AT, ENDS_AT, reservationId)).isTrue();
    }
}
