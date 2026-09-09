package com.callbot.ai.controller;

import java.util.List;
import java.util.UUID;

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
import com.callbot.ai.dto.RestaurantHoursRequest;
import com.callbot.ai.dto.RestaurantHoursResponse;
import com.callbot.ai.service.RestaurantHoursService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/restaurant-hours")
@RequiredArgsConstructor
public class RestaurantHoursController {

    private final RestaurantHoursService hoursService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantHoursResponse create(@Valid @RequestBody RestaurantHoursRequest request, Authentication authentication) {
        return hoursService.create(request, AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping
    public List<RestaurantHoursResponse> list(@RequestParam(required = false) UUID restaurantId,
            Authentication authentication) {
        return hoursService.list(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping("/{id}")
    public RestaurantHoursResponse get(@PathVariable UUID id, Authentication authentication) {
        return hoursService.get(id, AuthenticatedCaller.emailOf(authentication));
    }

    @PutMapping("/{id}")
    public RestaurantHoursResponse update(@PathVariable UUID id, @Valid @RequestBody RestaurantHoursRequest request, Authentication authentication) {
        return hoursService.update(id, request, AuthenticatedCaller.emailOf(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        hoursService.delete(id, AuthenticatedCaller.emailOf(authentication));
    }
}
