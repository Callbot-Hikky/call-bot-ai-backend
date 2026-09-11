package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.dto.AvailabilityResponse;
import com.callbot.ai.dto.CallContextResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantHours;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantHoursRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

@ExtendWith(MockitoExtension.class)
class CallContextServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private RestaurantHoursRepository hoursRepository;
    @Mock
    private RestaurantTableRepository tableRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @InjectMocks
    private CallContextService callContextService;

    private static final String PHONE = "+33100000001";
    // 2030-03-01 is a Friday; 19:00 UTC is 20:00 in Europe/Paris.
    private static final OffsetDateTime SLOT = OffsetDateTime.parse("2030-03-01T19:00:00Z");

    private final UUID restaurantId = UUID.randomUUID();

    private Restaurant restaurant() {
        return Restaurant.builder()
                .id(restaurantId)
                .name("Chez Test")
                .phoneNumber(PHONE)
                .timezone("Europe/Paris")
                .locale("fr")
                .attributes(Map.of("halal", true))
                .build();
    }

    private RestaurantTable table(int capacity) {
        return RestaurantTable.builder()
                .id(UUID.randomUUID())
                .restaurantId(restaurantId)
                .name("T" + capacity)
                .capacity(capacity)
                .isActive(true)
                .build();
    }

    @Test
    void context_returnsRestaurantHoursAndAttributes() {
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(table(2), table(6)));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(
                RestaurantHours.builder().dayOfWeek((short) 4).service("dinner")
                        .opensAt(LocalTime.of(19, 0)).closesAt(LocalTime.of(23, 0)).isClosed(false).build()));

        CallContextResponse context = callContextService.context(PHONE);

        assertThat(context.restaurant().name()).isEqualTo("Chez Test");
        assertThat(context.attributes()).containsEntry("halal", true);
        assertThat(context.hours()).hasSize(1);
        // Les tables peuvent être combinées : le plafond = min(15, capacité
        // totale) = min(15, 2 + 6) = 8, et non plus la plus grande table isolée.
        assertThat(context.policies().maxPartySize()).isEqualTo(8);
    }

    @Test
    void context_maxPartySize_cappedAt15() {
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        // Capacité totale 8+6+6 = 20 > 15 → plafonné à 15.
        when(tableRepository.findByRestaurantId(restaurantId))
                .thenReturn(List.of(table(8), table(6), table(6)));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());

        assertThat(callContextService.context(PHONE).policies().maxPartySize()).isEqualTo(15);
    }

    @Test
    void context_whenPhoneUnknown_throws() {
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> callContextService.context(PHONE))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void availability_whenTableFree_returnsAvailable() {
        RestaurantTable free = table(4);
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(free));
        when(reservationRepository.findBusyTableIds(eq(restaurantId), any(), any())).thenReturn(List.of());

        AvailabilityResponse response = callContextService.availability(PHONE, SLOT, null, 2);

        assertThat(response.available()).isTrue();
        assertThat(response.tableId()).isEqualTo(free.getId());
        assertThat(response.reason()).isNull();
        // Default sitting duration when no end time is given.
        assertThat(response.endsAt()).isEqualTo(SLOT.plusMinutes(90));
    }

    @Test
    void availability_whenPartyTooLargeForEveryTable_refuses() {
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(table(2)));

        AvailabilityResponse response = callContextService.availability(PHONE, SLOT, null, 8);

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("no_table");
        assertThat(response.alternatives()).isEmpty();
    }

    @Test
    void availability_whenSlotTaken_proposesLaterAlternatives() {
        RestaurantTable only = table(4);
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(only));
        // Busy for the requested slot, free for every probed slot afterwards.
        when(reservationRepository.findBusyTableIds(eq(restaurantId), eq(SLOT), any()))
                .thenReturn(List.of(only.getId()));
        when(reservationRepository.findBusyTableIds(eq(restaurantId), eq(SLOT.plusMinutes(30)), any()))
                .thenReturn(List.of());
        when(reservationRepository.findBusyTableIds(eq(restaurantId), eq(SLOT.plusMinutes(60)), any()))
                .thenReturn(List.of());
        when(reservationRepository.findBusyTableIds(eq(restaurantId), eq(SLOT.plusMinutes(90)), any()))
                .thenReturn(List.of());

        AvailabilityResponse response = callContextService.availability(PHONE, SLOT, null, 4);

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("no_table");
        assertThat(response.alternatives()).hasSize(3);
        assertThat(response.alternatives().get(0).startsAt()).isEqualTo(SLOT.plusMinutes(30));
        assertThat(response.alternatives().get(0).tableId()).isEqualTo(only.getId());
    }

    @Test
    void availability_whenPartyExceedsCap_refusesPartyTooLarge() {
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));

        AvailabilityResponse response = callContextService.availability(PHONE, SLOT, null, 16);

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("party_too_large");
        assertThat(response.tableIds()).isEmpty();
    }

    @Test
    void availability_whenNoSingleTableFits_combinesFreeTables() {
        RestaurantTable big = table(8);
        RestaurantTable medium = table(4);
        RestaurantTable small = table(4);
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(big, medium, small));
        when(reservationRepository.findBusyTableIds(eq(restaurantId), any(), any())).thenReturn(List.of());

        // 10 personnes : aucune table unique ne suffit → combinaison (8 + 4).
        AvailabilityResponse response = callContextService.availability(PHONE, SLOT, null, 10);

        assertThat(response.available()).isTrue();
        assertThat(response.reason()).isNull();
        assertThat(response.tableIds()).hasSize(2);
        // Plus grandes tables d'abord : la 8 puis une 4 (somme 12 ≥ 10).
        assertThat(response.tableIds().get(0)).isEqualTo(big.getId());
        assertThat(response.tableId()).isEqualTo(big.getId());
    }

    @Test
    void availability_whenEvenCombinedTablesAreTooSmall_refusesNoTable() {
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());
        // Capacité totale 2 + 2 = 4, groupe de 6 (≤ 15) : impossible même en combinant.
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(table(2), table(2)));
        when(reservationRepository.findBusyTableIds(eq(restaurantId), any(), any())).thenReturn(List.of());

        AvailabilityResponse response = callContextService.availability(PHONE, SLOT, null, 6);

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("no_table");
    }

    @Test
    void availability_whenRestaurantClosedAtThatTime_refusesWithClosed() {
        when(restaurantRepository.findByPhoneNumber(PHONE)).thenReturn(java.util.Optional.of(restaurant()));
        // Friday lunch only: the 20:00 Paris slot falls outside opening hours.
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(
                RestaurantHours.builder().dayOfWeek((short) 4).service("lunch")
                        .opensAt(LocalTime.of(12, 0)).closesAt(LocalTime.of(14, 30)).isClosed(false).build()));

        AvailabilityResponse response = callContextService.availability(PHONE, SLOT, null, 2);

        assertThat(response.available()).isFalse();
        assertThat(response.reason()).isEqualTo("closed");
    }
}
