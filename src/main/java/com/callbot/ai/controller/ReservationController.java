package com.callbot.ai.controller;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse create(@Valid @RequestBody ReservationRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return reservationService.create(request);
    }

    @GetMapping
    public List<ReservationResponse> list(@RequestParam(required = false) UUID restaurantId,
            @RequestParam(required = false) String expand, Authentication authentication) {
        if (restaurantId != null) {
            restaurantAccess.requireOwned(restaurantId, authentication);
            return reservationService.list(restaurantId, parseExpand(expand));
        }
        Set<UUID> owned = restaurantAccess.ownedRestaurantIds(authentication);
        return reservationService.list(null, parseExpand(expand)).stream()
                .filter(r -> owned.contains(r.restaurantId()))
                .toList();
    }

    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable UUID id,
            @RequestParam(required = false) String expand, Authentication authentication) {
        ReservationResponse reservation = reservationService.get(id, parseExpand(expand));
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
        restaurantAccess.requireOwned(reservationService.get(id, Set.of()).restaurantId(), authentication);
        return reservationService.rescheduleSlots(id, fromDate, partySize);
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
        restaurantAccess.requireOwned(reservationService.get(id, Set.of()).restaurantId(), authentication);
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return reservationService.update(id, request, notify);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        restaurantAccess.requireOwned(reservationService.get(id, Set.of()).restaurantId(), authentication);
        reservationService.delete(id);
    }
}
