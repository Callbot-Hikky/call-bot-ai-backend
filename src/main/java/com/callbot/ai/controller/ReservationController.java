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
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse create(@Valid @RequestBody ReservationRequest request) {
        return reservationService.create(request);
    }

    @GetMapping
    public List<ReservationResponse> list(@RequestParam(required = false) UUID restaurantId,
            @RequestParam(required = false) String expand) {
        return reservationService.list(restaurantId, parseExpand(expand));
    }

    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable UUID id,
            @RequestParam(required = false) String expand) {
        return reservationService.get(id, parseExpand(expand));
    }

    /** Slots free for rescheduling this reservation over a 7-day window (defaults to today).
     *  {@code partySize} lets the client preview slots for a different table size without
     *  writing the change (so the picker can react as the counter is edited). */
    @GetMapping("/{id}/reschedule-slots")
    public RescheduleSlotsResponse rescheduleSlots(@PathVariable UUID id,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) Integer partySize) {
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

    @PutMapping("/{id}")
    public ReservationResponse update(@PathVariable UUID id, @Valid @RequestBody ReservationRequest request) {
        return reservationService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        reservationService.delete(id);
    }
}
