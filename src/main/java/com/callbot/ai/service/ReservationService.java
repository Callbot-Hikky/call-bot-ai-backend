package com.callbot.ai.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.dto.RestaurantSummaryResponse;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantHours;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.notification.ReservationCreatedEvent;
import com.callbot.ai.notification.ReservationUpdatedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantHoursRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

    /** How far apart candidate slots are placed (30 min = standard restaurant granularity). */
    private static final Duration SLOT_STEP = Duration.ofMinutes(30);

    private final ReservationRepository reservationRepository;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantTableRepository tableRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantHoursRepository hoursRepository;
    private final ApplicationEventPublisher events;

    public ReservationResponse create(ReservationRequest request) {
        requireRestaurant(request.restaurantId());
        Reservation reservation = Reservation.builder()
                .restaurantId(request.restaurantId())
                .customerId(request.customerId())
                .tableId(request.tableId())
                // La détection d'occupation lit la table de liaison : une résa
                // mono-table doit donc aussi y figurer (dérivée du tableId).
                .tableIds(singleOrEmpty(request.tableId()))
                .callId(request.callId())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .partySize(request.partySize())
                .status(request.status() != null ? request.status() : "pending")
                .source(request.source() != null ? request.source() : "callbot")
                .notes(request.notes())
                .build();
        Reservation saved = reservationRepository.save(reservation);
        events.publishEvent(new ReservationCreatedEvent(saved.getId()));
        return ReservationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> list(UUID restaurantId, Set<String> expand) {
        List<Reservation> reservations = restaurantId != null
                ? reservationRepository.findByRestaurantId(restaurantId)
                : reservationRepository.findAll();
        return reservations.stream().map(r -> toResponse(r, expand)).toList();
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id, Set<String> expand) {
        return toResponse(find(id), expand);
    }

    public ReservationResponse update(UUID id, ReservationRequest request, boolean notify) {
        Reservation reservation = find(id);
        reservation.setCustomerId(request.customerId());
        reservation.setTableId(request.tableId());
        reservation.setTableIds(singleOrEmpty(request.tableId()));
        reservation.setCallId(request.callId());
        reservation.setStartsAt(request.startsAt());
        reservation.setEndsAt(request.endsAt());
        reservation.setPartySize(request.partySize());
        if (request.status() != null) {
            reservation.setStatus(request.status());
        }
        if (request.source() != null) {
            reservation.setSource(request.source());
        }
        reservation.setNotes(request.notes());
        Reservation saved = reservationRepository.save(reservation);
        if (notify) {
            events.publishEvent(new ReservationUpdatedEvent(saved.getId()));
        }
        return ReservationResponse.from(saved);
    }

    public void delete(UUID id) {
        if (!reservationRepository.existsById(id)) {
            throw new ResourceNotFoundException("Reservation", id);
        }
        reservationRepository.deleteById(id);
    }

    /**
     * Lists slots free for rescheduling this reservation, over a 7-day rolling window
     * starting from {@code fromDate} (defaults to today in the restaurant's timezone).
     * The reservation itself is excluded from busy tables — otherwise it would block its
     * own slot on the current day.
     */
    @Transactional(readOnly = true)
    public RescheduleSlotsResponse rescheduleSlots(UUID reservationId, LocalDate fromDate, Integer partySizeOverride) {
        Reservation reservation = find(reservationId);
        Restaurant restaurant = restaurantRepository.findById(reservation.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", reservation.getRestaurantId()));

        ZoneId zone = ZoneId.of(restaurant.getTimezone());
        LocalDate start = fromDate != null ? fromDate : LocalDate.now(zone);
        Duration duration = Duration.between(reservation.getStartsAt(), reservation.getEndsAt());
        // Preview: honour a party-size override so the picker updates before the resa is saved.
        int partySize;
        if (partySizeOverride != null && partySizeOverride > 0) {
            partySize = partySizeOverride;
        } else if (reservation.getPartySize() != null) {
            partySize = reservation.getPartySize();
        } else {
            partySize = 1;
        }

        List<RestaurantHours> hours = hoursRepository.findByRestaurantId(restaurant.getId());
        List<RestaurantTable> candidates = tableRepository.findByRestaurantId(restaurant.getId()).stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
                .filter(t -> t.getCapacity() >= partySize)
                .sorted(Comparator.comparing(RestaurantTable::getCapacity))
                .toList();

        List<RescheduleSlotsResponse.Day> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            LocalDate date = start.plusDays(i);
            days.add(new RescheduleSlotsResponse.Day(date,
                    slotsForDay(restaurant, zone, hours, candidates, date, duration, reservationId)));
        }
        return new RescheduleSlotsResponse(days);
    }

    /** Walks through the day in 30-min steps and keeps steps where a suitable table is free. */
    private List<RescheduleSlotsResponse.Slot> slotsForDay(Restaurant restaurant, ZoneId zone,
            List<RestaurantHours> hours, List<RestaurantTable> candidates, LocalDate date,
            Duration duration, UUID excludeReservationId) {
        // DB convention: 0 = Monday ... 6 = Sunday.
        short dayOfWeek = (short) (date.getDayOfWeek().getValue() - 1);
        List<RestaurantHours> openWindows = hours.stream()
                .filter(h -> h.getDayOfWeek() == dayOfWeek)
                .filter(h -> !Boolean.TRUE.equals(h.getIsClosed()))
                .sorted(Comparator.comparing(RestaurantHours::getOpensAt))
                .toList();
        // No configured hours = always open (mirrors CallContextService.isOpen).
        if (hours.isEmpty()) {
            return slotsInWindow(restaurant, zone, candidates, date, LocalTime.of(11, 0),
                    LocalTime.of(23, 0), duration, excludeReservationId);
        }
        List<RescheduleSlotsResponse.Slot> slots = new ArrayList<>();
        for (RestaurantHours window : openWindows) {
            slots.addAll(slotsInWindow(restaurant, zone, candidates, date, window.getOpensAt(),
                    window.getClosesAt(), duration, excludeReservationId));
        }
        return slots;
    }

    private List<RescheduleSlotsResponse.Slot> slotsInWindow(Restaurant restaurant, ZoneId zone,
            List<RestaurantTable> candidates, LocalDate date, LocalTime opensAt, LocalTime closesAt,
            Duration duration, UUID excludeReservationId) {
        List<RescheduleSlotsResponse.Slot> slots = new ArrayList<>();
        // Guard: a null/zero duration would loop forever (cursor never advances past closesAt).
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return slots;
        }
        // Guard: LocalTime wraps at midnight — if closesAt <= opensAt, walking forward with
        // cursor.plus(30min) would either exit immediately or (worse) loop after wrapping.
        if (!closesAt.isAfter(opensAt)) {
            return slots;
        }
        // Ne pas proposer de créneaux dans le passé — utile pour la journée en cours,
        // où opensAt peut être 12h alors qu'il est déjà 15h.
        OffsetDateTime nowInZone = OffsetDateTime.now(zone);
        LocalTime cursor = opensAt;
        // Hard cap: at most one slot per SLOT_STEP inside a single day (48 * 2 = 96 iterations).
        int safety = 0;
        while (!cursor.plus(duration).isAfter(closesAt) && safety++ < 200) {
            OffsetDateTime startsAt = ZonedDateTime.of(date, cursor, zone).toOffsetDateTime();
            OffsetDateTime endsAt = startsAt.plus(duration);
            if (!startsAt.isBefore(nowInZone)) {
                RestaurantTable free = firstFreeTable(restaurant.getId(), candidates, startsAt, endsAt,
                        excludeReservationId);
                if (free != null) {
                    slots.add(new RescheduleSlotsResponse.Slot(startsAt, endsAt, free.getId(), free.getCapacity()));
                }
            }
            LocalTime next = cursor.plus(SLOT_STEP);
            // LocalTime wraps at midnight — detect wrap and bail rather than looping forever.
            if (!next.isAfter(cursor)) {
                break;
            }
            cursor = next;
        }
        return slots;
    }

    private RestaurantTable firstFreeTable(UUID restaurantId, List<RestaurantTable> candidates,
            OffsetDateTime startsAt, OffsetDateTime endsAt, UUID excludeReservationId) {
        if (candidates.isEmpty()) {
            return null;
        }
        Set<UUID> busy = new HashSet<>(reservationRepository.findBusyTableIdsExcluding(
                restaurantId, startsAt, endsAt, excludeReservationId));
        return candidates.stream()
                .filter(t -> !busy.contains(t.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Builds the response, embedding the related resources requested via ?expand=.
     * When not expanded (or when a link is null), table/customer stay null and are
     * omitted from the JSON.
     */
    private ReservationResponse toResponse(Reservation reservation, Set<String> expand) {
        RestaurantTableResponse table = null;
        List<RestaurantTableResponse> tables = null;
        CustomerResponse customer = null;
        RestaurantSummaryResponse restaurant = null;
        if (expand.contains("table")) {
            if (reservation.getTableId() != null) {
                table = tableRepository.findById(reservation.getTableId())
                        .map(RestaurantTableResponse::from)
                        .orElse(null);
            }
            Set<UUID> ids = reservation.getTableIds();
            if (ids != null && !ids.isEmpty()) {
                List<RestaurantTableResponse> resolved = ids.stream()
                        .flatMap(id -> tableRepository.findById(id)
                                .map(RestaurantTableResponse::from)
                                .stream())
                        .toList();
                tables = resolved.isEmpty() ? null : resolved;
            }
        }
        if (expand.contains("customer") && reservation.getCustomerId() != null) {
            customer = customerRepository.findById(reservation.getCustomerId())
                    .map(CustomerResponse::from)
                    .orElse(null);
        }
        if (expand.contains("restaurant") && reservation.getRestaurantId() != null) {
            restaurant = restaurantRepository.findById(reservation.getRestaurantId())
                    .map(RestaurantSummaryResponse::from)
                    .orElse(null);
        }
        return ReservationResponse.from(reservation, table, tables, customer, restaurant);
    }

    private Reservation find(UUID id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }

    /** Table unique → ensemble (éventuellement vide) pour la table de liaison. */
    private static Set<UUID> singleOrEmpty(UUID tableId) {
        Set<UUID> ids = new HashSet<>();
        if (tableId != null) {
            ids.add(tableId);
        }
        return ids;
    }
}
