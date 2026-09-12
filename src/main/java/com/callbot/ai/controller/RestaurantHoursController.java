package com.callbot.ai.controller;

import java.util.List;
import java.util.UUID;

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

import com.callbot.ai.dto.RestaurantHoursRequest;
import com.callbot.ai.dto.RestaurantHoursResponse;
import com.callbot.ai.service.RestaurantHoursService;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.callbot.ai.security.RestaurantAccess;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/restaurant-hours")
@RequiredArgsConstructor
public class RestaurantHoursController {

    private final RestaurantHoursService hoursService;
    private final RestaurantAccess restaurantAccess;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantHoursResponse create(@Valid @RequestBody RestaurantHoursRequest request,
            Authentication authentication) {
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return hoursService.create(request);
    }

    @GetMapping
    public List<RestaurantHoursResponse> list(@RequestParam(required = false) UUID restaurantId,
            Authentication authentication) {
        if (restaurantId != null) {
            restaurantAccess.requireOwned(restaurantId, authentication);
            return hoursService.list(restaurantId);
        }
        return hoursService.listOwned(restaurantAccess.ownedRestaurantIds(authentication));
    }

    @GetMapping("/{id}")
    public RestaurantHoursResponse get(@PathVariable UUID id, Authentication authentication) {
        RestaurantHoursResponse hours = hoursService.get(id);
        restaurantAccess.requireOwned(hours.restaurantId(), authentication);
        return hours;
    }

    @PutMapping("/{id}")
    public RestaurantHoursResponse update(@PathVariable UUID id, @Valid @RequestBody RestaurantHoursRequest request,
            Authentication authentication) {
        restaurantAccess.requireOwned(hoursService.get(id).restaurantId(), authentication);
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return hoursService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        restaurantAccess.requireOwned(hoursService.get(id).restaurantId(), authentication);
        hoursService.delete(id);
    }
}
