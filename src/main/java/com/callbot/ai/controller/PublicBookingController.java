package com.callbot.ai.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.PublicReservationRequest;
import com.callbot.ai.dto.PublicReservationResponse;
import com.callbot.ai.dto.PublicRescheduleRequest;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.service.PublicBookingService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Reservation en ligne, sans authentification : le client vient du lien ou du QR
 * « Reserver une table » du restaurateur. Trois routes, listees une a une dans
 * SecurityConfig ; tout le reste de /api/public reste protege.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicBookingController {

    private final PublicBookingService bookingService;

    @GetMapping("/restaurants/{restaurantId}/slots")
    public RescheduleSlotsResponse slots(@PathVariable UUID restaurantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(defaultValue = "2") int partySize) {
        return bookingService.slots(restaurantId, fromDate, partySize);
    }

    @PostMapping("/restaurants/{restaurantId}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public PublicReservationResponse create(@PathVariable UUID restaurantId,
            @Valid @RequestBody PublicReservationRequest request) {
        return bookingService.create(restaurantId, request);
    }

    // {token} est le jeton public recu dans le message de confirmation, pas l'identifiant interne.
    @GetMapping("/reservations/{token}")
    public PublicReservationResponse get(@PathVariable UUID token) {
        return bookingService.get(token);
    }

    @GetMapping("/reservations/{token}/slots")
    public RescheduleSlotsResponse rescheduleSlots(@PathVariable UUID token,
            @RequestParam(required = false) Integer partySize) {
        return bookingService.rescheduleSlots(token, partySize);
    }

    @PutMapping("/reservations/{token}")
    public PublicReservationResponse reschedule(@PathVariable UUID token,
            @Valid @RequestBody PublicRescheduleRequest request) {
        return bookingService.reschedule(token, request);
    }
}
