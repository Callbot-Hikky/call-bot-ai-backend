package com.callbot.ai.controller;

import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.AvailabilityResponse;
import com.callbot.ai.dto.CallContextResponse;
import com.callbot.ai.service.CallContextService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** Read-only endpoints the AI microservice calls while a customer is on the line. */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
@Validated
public class CallContextController {

    private final CallContextService callContextService;

    @GetMapping("/context")
    public CallContextResponse context(@RequestParam @NotBlank String restaurantPhone) {
        return callContextService.context(restaurantPhone);
    }

    @GetMapping("/availability")
    public AvailabilityResponse availability(
            @RequestParam @NotBlank String restaurantPhone,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startsAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endsAt,
            @RequestParam @Positive int partySize) {
        return callContextService.availability(restaurantPhone, startsAt, endsAt, partySize);
    }
}
