package com.callbot.ai.service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

import lombok.RequiredArgsConstructor;

/** Read-only data the AI microservice needs while a call is in progress. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CallContextService {

    /** Assumed sitting duration when the caller does not state an end time. */
    private static final Duration DEFAULT_DURATION = Duration.ofMinutes(90);
    private static final Duration ALTERNATIVE_STEP = Duration.ofMinutes(30);
    private static final int MAX_ALTERNATIVES = 3;
    private static final int MAX_PROBES = 8;

    private final RestaurantRepository restaurantRepository;
    private final RestaurantHoursRepository hoursRepository;
    private final RestaurantTableRepository tableRepository;
    private final ReservationRepository reservationRepository;

    public CallContextResponse context(String restaurantPhone) {
        Restaurant restaurant = findByPhone(restaurantPhone);
        List<RestaurantTable> tables = activeTables(restaurant.getId());

        List<CallContextResponse.OpeningHours> hours = hoursRepository.findByRestaurantId(restaurant.getId())
                .stream()
                .sorted(Comparator.comparing(RestaurantHours::getDayOfWeek)
                        .thenComparing(RestaurantHours::getOpensAt))
                .map(h -> new CallContextResponse.OpeningHours(h.getDayOfWeek(), h.getService(),
                        h.getOpensAt(), h.getClosesAt(), h.getIsClosed()))
                .toList();

        // Caps what the AI may accept without a human check.
        Integer maxPartySize = tables.stream()
                .map(RestaurantTable::getCapacity)
                .max(Integer::compareTo)
                .orElse(null);

        return new CallContextResponse(
                new CallContextResponse.Restaurant(restaurant.getId(), restaurant.getName(),
                        restaurant.getPhoneNumber(), restaurant.getAddress(), restaurant.getCity(),
                        restaurant.getPostalCode(), restaurant.getTimezone(), restaurant.getLocale()),
                hours,
                restaurant.getAttributes(),
                new CallContextResponse.Policies(maxPartySize, (int) DEFAULT_DURATION.toMinutes()));
    }

    public AvailabilityResponse availability(String restaurantPhone, OffsetDateTime startsAt,
            OffsetDateTime endsAt, int partySize) {
        Restaurant restaurant = findByPhone(restaurantPhone);
        OffsetDateTime end = endsAt != null ? endsAt : startsAt.plus(DEFAULT_DURATION);
        Duration duration = Duration.between(startsAt, end);

        List<RestaurantHours> hours = hoursRepository.findByRestaurantId(restaurant.getId());
        List<RestaurantTable> candidates = activeTables(restaurant.getId()).stream()
                .filter(t -> t.getCapacity() >= partySize)
                // Smallest suitable table first, to keep large tables free for large parties.
                .sorted(Comparator.comparing(RestaurantTable::getCapacity))
                .toList();

        if (!isOpen(restaurant, hours, startsAt)) {
            return new AvailabilityResponse(false, "closed", null, startsAt, end, partySize,
                    alternatives(restaurant, hours, candidates, startsAt, duration));
        }

        RestaurantTable free = firstFreeTable(restaurant.getId(), candidates, startsAt, end);
        if (free == null) {
            return new AvailabilityResponse(false, "no_table", null, startsAt, end, partySize,
                    alternatives(restaurant, hours, candidates, startsAt, duration));
        }
        return new AvailabilityResponse(true, null, free.getId(), startsAt, end, partySize, List.of());
    }

    /** Probes later slots so the AI can counter-propose instead of just refusing. */
    private List<AvailabilityResponse.Slot> alternatives(Restaurant restaurant, List<RestaurantHours> hours,
            List<RestaurantTable> candidates, OffsetDateTime startsAt, Duration duration) {
        List<AvailabilityResponse.Slot> slots = new ArrayList<>();
        OffsetDateTime probe = startsAt;
        for (int i = 0; i < MAX_PROBES && slots.size() < MAX_ALTERNATIVES; i++) {
            probe = probe.plus(ALTERNATIVE_STEP);
            OffsetDateTime probeEnd = probe.plus(duration);
            if (!isOpen(restaurant, hours, probe)) {
                continue;
            }
            RestaurantTable free = firstFreeTable(restaurant.getId(), candidates, probe, probeEnd);
            if (free != null) {
                slots.add(new AvailabilityResponse.Slot(probe, probeEnd, free.getId(), free.getCapacity()));
            }
        }
        return slots;
    }

    private RestaurantTable firstFreeTable(UUID restaurantId, List<RestaurantTable> candidates,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (candidates.isEmpty()) {
            return null;
        }
        Set<UUID> busy = Set.copyOf(reservationRepository.findBusyTableIds(restaurantId, startsAt, endsAt));
        return candidates.stream()
                .filter(t -> !busy.contains(t.getId()))
                .findFirst()
                .orElse(null);
    }

    /** No configured hours means always open, so an incomplete setup never blocks a booking. */
    private boolean isOpen(Restaurant restaurant, List<RestaurantHours> hours, OffsetDateTime instant) {
        if (hours.isEmpty()) {
            return true;
        }
        ZonedDateTime local = instant.atZoneSameInstant(ZoneId.of(restaurant.getTimezone()));
        // DB convention: 0 = Monday ... 6 = Sunday.
        short dayOfWeek = (short) (local.getDayOfWeek().getValue() - 1);
        LocalTime time = local.toLocalTime();
        return hours.stream().anyMatch(h -> !Boolean.TRUE.equals(h.getIsClosed())
                && h.getDayOfWeek() == dayOfWeek
                && !time.isBefore(h.getOpensAt())
                && time.isBefore(h.getClosesAt()));
    }

    private List<RestaurantTable> activeTables(UUID restaurantId) {
        return tableRepository.findByRestaurantId(restaurantId).stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
                .toList();
    }

    private Restaurant findByPhone(String restaurantPhone) {
        return restaurantRepository.findByPhoneNumber(restaurantPhone)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantPhone));
    }
}
