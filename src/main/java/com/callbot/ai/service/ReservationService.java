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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.dto.PendingTopUpResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.dto.RestaurantSummaryResponse;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantHours;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.notification.ReservationCreatedEvent;
import com.callbot.ai.notification.ReservationUpdatedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantHoursRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;
import com.callbot.ai.security.OrganizationScope;

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
    private final OrganizationScope scope;
    private final GuaranteePolicy guaranteePolicy;
    private final PartySizeChangePolicy partySizeChangePolicy;
    private final PartySizeTopUpService topUps;
    private final PartySizeRefund partySizeRefund;
    private final ReservationChargeRepository charges;

    public ReservationResponse create(ReservationRequest request, String callerEmail) {
        Restaurant restaurant = scope.ownedRestaurant(request.restaurantId(), callerEmail);
        Reservation reservation = Reservation.builder()
                .restaurantId(request.restaurantId())
                .customerId(request.customerId())
                .tableId(request.tableId())
                .callId(request.callId())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .partySize(request.partySize())
                .status(request.status() != null ? request.status() : "pending")
                .source(request.source() != null ? request.source() : "callbot")
                .notes(request.notes())
                .build();
        guaranteePolicy.applyOnCreation(reservation, restaurant, exemptingStaff(request, callerEmail));

        Reservation saved = reservationRepository.save(reservation);
        events.publishEvent(new ReservationCreatedEvent(saved.getId()));
        return ReservationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> list(UUID restaurantId, Set<String> expand, String callerEmail) {
        List<Reservation> reservations;
        if (restaurantId != null) {
            scope.requireOwnedRestaurant(restaurantId, callerEmail);
            reservations = reservationRepository.findByRestaurantId(restaurantId);
        } else {
            List<UUID> restaurantIds = scope.ownedRestaurantIds(callerEmail).orElse(null);
            if (restaurantIds == null) {
                reservations = reservationRepository.findAll();
            } else {
                reservations = restaurantIds.isEmpty()
                        ? List.of()
                        : reservationRepository.findByRestaurantIdIn(restaurantIds);
            }
        }
        return reservations.stream().map(r -> toResponse(r, expand)).toList();
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id, Set<String> expand, String callerEmail) {
        return toResponse(find(id, callerEmail), expand);
    }

    /**
     * Applies an edit from the dashboard.
     *
     * <p>A rise in covers on a paid reservation does not take effect here: the rule sends
     * it off to be paid for, and the reservation keeps the party it was sold with until
     * the money is in. Everything else in the request is still written — the staff member
     * correcting a note alongside the covers should not lose the note.
     *
     * <p>A fall does take effect, and takes any request outstanding with it: that request
     * priced the difference against the party that has just changed.
     */
    public ReservationResponse update(UUID id, ReservationRequest request, boolean notify, String callerEmail) {
        Reservation reservation = find(id, callerEmail);
        // Before anything is written: a change in covers goes through the rule. It weighs
        // the requested slot, since that is the slot the reservation will occupy.
        PartySizeChange verdict = partySizeChangePolicy.decide(
                reservation, request.partySize(), request.startsAt(), request.endsAt());
        Integer requestedPartySize = request.partySize();
        // A party that shrank gives its covers back, whoever pressed the button. A diner
        // doing this from their own link and a staff member doing it for them over the
        // telephone are the same event, and answering them differently would make the
        // refund depend on which door the change came through.
        if (verdict != PartySizeChange.COLLECT_TOP_UP && requestedPartySize != null
                && reservation.getPartySize() != null) {
            partySizeRefund.handBackCoversGivenUp(
                    reservation, reservation.getPartySize(), requestedPartySize);
        }
        reservation.setCustomerId(request.customerId());
        reservation.setTableId(request.tableId());
        reservation.setCallId(request.callId());
        reservation.setStartsAt(request.startsAt());
        reservation.setEndsAt(request.endsAt());
        if (verdict != PartySizeChange.COLLECT_TOP_UP) {
            reservation.setPartySize(requestedPartySize);
        }
        if (request.status() != null) {
            reservation.setStatus(request.status());
        }
        if (request.source() != null) {
            reservation.setSource(request.source());
        }
        reservation.setNotes(request.notes());
        Reservation saved = reservationRepository.save(reservation);

        if (verdict == PartySizeChange.APPLY_AND_LAPSE_TOP_UP) {
            // The party this request was priced against has just moved. Leaving the link
            // alive would let the diner buy a difference against a number that is gone.
            topUps.lapsePendingFor(saved.getId(), "the party was revised down");
        }
        if (verdict == PartySizeChange.COLLECT_TOP_UP) {
            // The diner is told about the money owed; a second "your booking changed"
            // message would announce a change that has not happened.
            return ReservationResponse.from(saved,
                    PendingTopUpResponse.of(topUps.open(saved, requestedPartySize)));
        }
        if (notify) {
            events.publishEvent(new ReservationUpdatedEvent(saved.getId()));
        }
        return ReservationResponse.from(saved, pendingTopUpOf(saved));
    }

    /**
     * Cancels a reservation. Deleting the row would destroy the trace of a payment —
     * Stripe keeps its own record either way — so the reservation is kept and marked
     * cancelled, which also frees the table.
     */
    public void delete(UUID id, String callerEmail) {
        Reservation reservation = find(id, callerEmail);
        if (ReservationStatus.CANCELLED.equals(reservation.getStatus())) {
            return;
        }
        // A request outstanding was for guests at a service that is not happening.
        topUps.lapsePendingFor(reservation.getId(), "the reservation was cancelled");

        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setCancelledAt(OffsetDateTime.now());
        reservationRepository.save(reservation);
    }

    /**
     * Lists slots free for rescheduling this reservation, over a 7-day rolling window
     * starting from {@code fromDate} (defaults to today in the restaurant's timezone).
     * The reservation itself is excluded from busy tables — otherwise it would block its
     * own slot on the current day.
     */
    @Transactional(readOnly = true)
    public RescheduleSlotsResponse rescheduleSlots(UUID reservationId, LocalDate fromDate,
            Integer partySizeOverride, String callerEmail) {
        return slotsFor(find(reservationId, callerEmail), fromDate, partySizeOverride);
    }

    /**
     * The same search, from a reservation already in hand.
     *
     * <p>Split from the entry point above so the diner's own modification page can ask the
     * identical question holding nothing but their token. One implementation, because two
     * would eventually answer differently and someone would pick a slot that was never free.
     */
    @Transactional(readOnly = true)
    public RescheduleSlotsResponse slotsFor(Reservation reservation, LocalDate fromDate,
            Integer partySizeOverride) {
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
                    slotsForDay(restaurant, zone, hours, candidates, date, duration,
                            reservation.getId())));
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
        CustomerResponse customer = null;
        RestaurantSummaryResponse restaurant = null;
        if (expand.contains("table") && reservation.getTableId() != null) {
            table = tableRepository.findById(reservation.getTableId())
                    .map(RestaurantTableResponse::from)
                    .orElse(null);
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
        return ReservationResponse.from(reservation, table, customer, restaurant,
                pendingTopUpOf(reservation));
    }

    /** The top-up awaiting settlement on this reservation, when there is one. */
    private PendingTopUpResponse pendingTopUpOf(Reservation reservation) {
        return charges.findByReservationIdAndKindAndStatus(
                        reservation.getId(), ChargeKind.PARTY_SIZE_TOP_UP, ChargeStatus.PENDING)
                .map(PendingTopUpResponse::of)
                .orElse(null);
    }

    /**
     * Loads a reservation the caller is allowed to see. A reservation belonging to
     * another organization is reported as missing rather than forbidden, so the API
     * never confirms that someone else's reservation exists.
     */
    private Reservation find(UUID id, String callerEmail) {
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
        scope.requireOwnedThrough(reservation.getRestaurantId(), "Reservation", id, callerEmail);
        return reservation;
    }

    /**
     * The staff member waiving the guarantee, if one is. Only a signed-in person can
     * waive: the exception has to be attributable, otherwise nobody can tell why a
     * paying mode brings in nothing.
     */
    private UUID exemptingStaff(ReservationRequest request, String callerEmail) {
        if (!Boolean.TRUE.equals(request.exemptGuarantee())) {
            return null;
        }
        return scope.userIdOf(callerEmail)
                .orElseThrow(() -> new InvalidRequestException(
                        "Only a signed-in staff member can waive a guarantee"));
    }
}
