package com.callbot.ai.service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

    /** Duree d'une table sans heure de fin annoncee : regle partagee avec la reservation en ligne. */
    private static final Duration DEFAULT_DURATION = BookingPolicy.DEFAULT_DURATION;
    private static final Duration ALTERNATIVE_STEP = Duration.ofMinutes(30);
    private static final int MAX_ALTERNATIVES = 3;
    private static final int MAX_PROBES = 8;

    /**
     * Plafond métier : au-delà, l'assistant vocal ne prend pas la réservation
     * (un très grand groupe relève d'un échange humain). En-deçà, un groupe qui
     * ne tient pas sur une seule table est réparti sur plusieurs tables libres.
     */
    private static final int MAX_PARTY_SIZE = BookingPolicy.MAX_PARTY_SIZE;

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

        // Plafond de groupe annoncé au bot : le maximum métier (15), borné par
        // la capacité totale du restaurant (inutile d'accepter 15 si les tables
        // ne totalisent que 10 places). Les tables peuvent être combinées, donc
        // on ne se limite plus à la plus grande table isolée.
        int totalCapacity = tables.stream()
                .mapToInt(RestaurantTable::getCapacity)
                .sum();
        Integer maxPartySize = tables.isEmpty() ? null : Math.min(MAX_PARTY_SIZE, totalCapacity);

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

        // Plafond métier : au-delà de 15, l'assistant ne prend pas la réservation.
        if (partySize > MAX_PARTY_SIZE) {
            return new AvailabilityResponse(false, "party_too_large", null, List.of(),
                    startsAt, end, partySize, List.of());
        }

        List<RestaurantHours> hours = hoursRepository.findByRestaurantId(restaurant.getId());

        if (!isOpen(restaurant, hours, startsAt)) {
            return new AvailabilityResponse(false, "closed", null, List.of(), startsAt, end, partySize,
                    alternatives(restaurant, hours, startsAt, duration, partySize));
        }

        List<RestaurantTable> chosen = freeTablesFor(restaurant.getId(), partySize, startsAt, end);
        if (chosen.isEmpty()) {
            return new AvailabilityResponse(false, "no_table", null, List.of(), startsAt, end, partySize,
                    alternatives(restaurant, hours, startsAt, duration, partySize));
        }
        List<UUID> ids = chosen.stream().map(RestaurantTable::getId).toList();
        return new AvailabilityResponse(true, null, ids.get(0), ids, startsAt, end, partySize, List.of());
    }

    /** Probes later slots so the AI can counter-propose instead of just refusing. */
    private List<AvailabilityResponse.Slot> alternatives(Restaurant restaurant, List<RestaurantHours> hours,
            OffsetDateTime startsAt, Duration duration, int partySize) {
        List<AvailabilityResponse.Slot> slots = new ArrayList<>();
        OffsetDateTime probe = startsAt;
        for (int i = 0; i < MAX_PROBES && slots.size() < MAX_ALTERNATIVES; i++) {
            probe = probe.plus(ALTERNATIVE_STEP);
            OffsetDateTime probeEnd = probe.plus(duration);
            if (!isOpen(restaurant, hours, probe)) {
                continue;
            }
            List<RestaurantTable> chosen = freeTablesFor(restaurant.getId(), partySize, probe, probeEnd);
            if (!chosen.isEmpty()) {
                int capacity = chosen.stream().mapToInt(RestaurantTable::getCapacity).sum();
                slots.add(new AvailabilityResponse.Slot(probe, probeEnd, chosen.get(0).getId(), capacity));
            }
        }
        return slots;
    }

    /**
     * Tables libres à retenir pour asseoir {@code partySize} sur la plage donnée :
     * une seule table si l'une suffit, sinon une combinaison de plusieurs tables
     * libres. Liste vide si le groupe ne peut être assis, même en combinant.
     */
    private List<RestaurantTable> freeTablesFor(UUID restaurantId, int partySize,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        Set<UUID> busy = Set.copyOf(reservationRepository.findBusyTableIds(restaurantId, startsAt, endsAt));
        List<RestaurantTable> free = activeTables(restaurantId).stream()
                .filter(t -> !busy.contains(t.getId()))
                .toList();
        return pickTables(free, partySize);
    }

    /** Sélectionne les tables : une seule suffisante (la plus petite), sinon combinaison. */
    private List<RestaurantTable> pickTables(List<RestaurantTable> free, int partySize) {
        // 1. Une seule table convient : on prend la plus petite suffisante pour ne
        //    pas gaspiller les grandes tables ni mobiliser plusieurs tables pour rien.
        Optional<RestaurantTable> single = free.stream()
                .filter(t -> t.getCapacity() >= partySize)
                .min(Comparator.comparing(RestaurantTable::getCapacity));
        if (single.isPresent()) {
            return List.of(single.get());
        }
        // 2. Sinon on combine les plus grandes tables libres jusqu'à la capacité
        //    demandée (minimise le nombre de tables mobilisées).
        List<RestaurantTable> byCapacityDesc = free.stream()
                .sorted(Comparator.comparing(RestaurantTable::getCapacity).reversed())
                .toList();
        List<RestaurantTable> picked = new ArrayList<>();
        int seated = 0;
        for (RestaurantTable table : byCapacityDesc) {
            picked.add(table);
            seated += table.getCapacity();
            if (seated >= partySize) {
                return picked;
            }
        }
        // Même en combinant toutes les tables libres, le groupe ne tient pas.
        return List.of();
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
