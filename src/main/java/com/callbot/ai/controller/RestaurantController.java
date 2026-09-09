package com.callbot.ai.controller;

import java.util.List;
import java.util.UUID;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.security.AuthenticatedCaller;
import com.callbot.ai.dto.GuaranteeSettingsRequest;
import com.callbot.ai.dto.GuaranteeSettingsResponse;
import com.callbot.ai.dto.RestaurantRequest;
import com.callbot.ai.dto.RestaurantResponse;
import com.callbot.ai.service.GuaranteeSettingsService;
import com.callbot.ai.service.RestaurantService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;
    private final GuaranteeSettingsService guaranteeSettingsService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantResponse create(@Valid @RequestBody RestaurantRequest request,
            Authentication authentication) {
        return restaurantService.create(request, AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping
    public List<RestaurantResponse> list(@RequestParam(required = false) UUID organizationId,
            Authentication authentication) {
        return restaurantService.list(organizationId, AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping("/{id}")
    public RestaurantResponse get(@PathVariable UUID id, Authentication authentication) {
        return restaurantService.get(id, AuthenticatedCaller.emailOf(authentication));
    }

    @PutMapping("/{id}")
    public RestaurantResponse update(@PathVariable UUID id,
            @Valid @RequestBody RestaurantRequest request, Authentication authentication) {
        return restaurantService.update(id, request, AuthenticatedCaller.emailOf(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        restaurantService.delete(id, AuthenticatedCaller.emailOf(authentication));
    }

    @PatchMapping("/{id}/attributes")
    public RestaurantResponse updateAttributes(@PathVariable UUID id,
            @RequestBody Map<String, Object> attributes, Authentication authentication) {
        return restaurantService.updateAttributes(id, attributes,
                AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping("/{id}/guarantee-settings")
    public GuaranteeSettingsResponse getGuaranteeSettings(@PathVariable UUID id,
            Authentication authentication) {
        return guaranteeSettingsService.get(id, AuthenticatedCaller.emailOf(authentication));
    }

    @PutMapping("/{id}/guarantee-settings")
    public GuaranteeSettingsResponse updateGuaranteeSettings(@PathVariable UUID id,
            @Valid @RequestBody GuaranteeSettingsRequest request, Authentication authentication) {
        return guaranteeSettingsService.update(id, request, AuthenticatedCaller.emailOf(authentication));
    }

    /** See {@code ReservationController#callerEmail}: the AI microservice is not a user. */
    private static String callerEmail(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        boolean isService = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SERVICE".equals(authority.getAuthority()));
        return isService ? null : authentication.getName();
    }
}
