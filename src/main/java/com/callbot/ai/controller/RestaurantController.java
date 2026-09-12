package com.callbot.ai.controller;

import java.util.List;
import java.util.UUID;
import java.util.Map;

import org.springframework.http.HttpStatus;
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

import com.callbot.ai.dto.RestaurantRequest;
import com.callbot.ai.dto.RestaurantResponse;
import com.callbot.ai.service.RestaurantService;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.callbot.ai.security.RestaurantAccess;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;
    private final RestaurantAccess restaurantAccess;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantResponse create(@Valid @RequestBody RestaurantRequest request, Authentication authentication) {
        restaurantAccess.requireOrganization(request.organizationId(), authentication);
        return restaurantService.create(request);
    }

    @GetMapping
    public List<RestaurantResponse> list(@RequestParam(required = false) UUID organizationId,
            Authentication authentication) {
        // Toujours l'organisation de l'utilisateur : le parametre ne sert qu'a la compatibilite.
        return restaurantService.list(restaurantAccess.organizationOf(authentication));
    }

    @GetMapping("/{id}")
    public RestaurantResponse get(@PathVariable UUID id, Authentication authentication) {
        restaurantAccess.requireOwned(id, authentication);
        return restaurantService.get(id);
    }

    @PutMapping("/{id}")
    public RestaurantResponse update(@PathVariable UUID id, @Valid @RequestBody RestaurantRequest request,
            Authentication authentication) {
        restaurantAccess.requireOwned(id, authentication);
        restaurantAccess.requireOrganization(request.organizationId(), authentication);
        return restaurantService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        restaurantAccess.requireOwned(id, authentication);
        restaurantService.delete(id);
    }

    @PatchMapping("/{id}/attributes")
    public RestaurantResponse updateAttributes(@PathVariable UUID id, @RequestBody Map<String, Object> attributes,
            Authentication authentication) {
        restaurantAccess.requireOwned(id, authentication);
        return restaurantService.updateAttributes(id, attributes);
    }
}
