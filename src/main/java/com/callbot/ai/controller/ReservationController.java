package com.callbot.ai.controller;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.security.AuthenticatedCaller;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.service.NoShowService;
import com.callbot.ai.service.ReservationService;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.callbot.ai.security.RestaurantAccess;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final RestaurantAccess restaurantAccess;
    private final NoShowService noShowService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse create(@Valid @RequestBody ReservationRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return reservationService.create(request, AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping
    public List<ReservationResponse> list(@RequestParam(required = false) UUID restaurantId,
            @RequestParam(required = false) String expand, Authentication authentication) {
        if (restaurantId != null) {
            restaurantAccess.requireOwned(restaurantId, authentication);
            return reservationService.list(restaurantId, parseExpand(expand), AuthenticatedCaller.emailOf(authentication));
        }
        return reservationService.listOwned(restaurantAccess.ownedRestaurantIds(authentication), parseExpand(expand));
    }

    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable UUID id,
            @RequestParam(required = false) String expand, Authentication authentication) {
        ReservationResponse reservation = reservationService.get(id, parseExpand(expand), AuthenticatedCaller.emailOf(authentication));
        restaurantAccess.requireOwned(reservation.restaurantId(), authentication);
        return reservation;
    }

    /** Slots free for rescheduling this reservation over a 7-day window (defaults to today).
     *  {@code partySize} lets the client preview slots for a different table size without
     *  writing the change (so the picker can react as the counter is edited). */
    @GetMapping("/{id}/reschedule-slots")
    public RescheduleSlotsResponse rescheduleSlots(@PathVariable UUID id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) Integer partySize, Authentication authentication) {
        restaurantAccess.requireOwned(reservationService.get(id, Set.of(), AuthenticatedCaller.emailOf(authentication)).restaurantId(), authentication);
        return reservationService.rescheduleSlots(id, fromDate, partySize, AuthenticatedCaller.emailOf(authentication));
    }

    /** Parses "table,customer" into the set of related resources to embed. */
    private Set<String> parseExpand(String expand) {
        if (expand == null || expand.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(expand.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** {@code notify=true} déclenche les notifications Discord (client + resto).
     *  On garde false par défaut : le staff qui bouge une table ou change un statut
     *  ne doit pas spammer le client. Le flag est activé par la page reschedule client. */
    @PutMapping("/{id}")
    public ReservationResponse update(@PathVariable UUID id,
            @Valid @RequestBody ReservationRequest request,
            @RequestParam(defaultValue = "false") boolean notify, Authentication authentication) {
        restaurantAccess.requireOwned(reservationService.get(id, Set.of(), AuthenticatedCaller.emailOf(authentication)).restaurantId(), authentication);
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return reservationService.update(id, request, notify, AuthenticatedCaller.emailOf(authentication));
    }

    /**
     * Records that the diner did not come. Never inferred: a table nobody marks is a
     * table that was honoured.
     */
    @PostMapping("/{id}/no-show")
    public ReservationResponse recordNoShow(@PathVariable UUID id, Authentication authentication) {
        return noShowService.record(id, AuthenticatedCaller.emailOf(authentication));
    }

    /** Takes the absence back, while the cancellation window is still open. */
    @DeleteMapping("/{id}/no-show")
    public ReservationResponse undoNoShow(@PathVariable UUID id, Authentication authentication) {
        return noShowService.undo(id, AuthenticatedCaller.emailOf(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        restaurantAccess.requireOwned(reservationService.get(id, Set.of(), AuthenticatedCaller.emailOf(authentication)).restaurantId(), authentication);
        reservationService.delete(id, AuthenticatedCaller.emailOf(authentication));
    }
}
