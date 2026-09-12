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

import com.callbot.ai.dto.RestaurantTableRequest;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.service.RestaurantTableService;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.callbot.ai.security.RestaurantAccess;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class RestaurantTableController {

    private final RestaurantTableService tableService;
    private final RestaurantAccess restaurantAccess;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantTableResponse create(@Valid @RequestBody RestaurantTableRequest request,
            Authentication authentication) {
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return tableService.create(request);
    }

    @GetMapping
    public List<RestaurantTableResponse> list(@RequestParam(required = false) UUID restaurantId,
            Authentication authentication) {
        if (restaurantId != null) {
            restaurantAccess.requireOwned(restaurantId, authentication);
            return tableService.list(restaurantId);
        }
        Set<UUID> owned = restaurantAccess.ownedRestaurantIds(authentication);
        return tableService.list(null).stream().filter(t -> owned.contains(t.restaurantId())).toList();
    }

    @GetMapping("/{id}")
    public RestaurantTableResponse get(@PathVariable UUID id, Authentication authentication) {
        RestaurantTableResponse table = tableService.get(id);
        restaurantAccess.requireOwned(table.restaurantId(), authentication);
        return table;
    }

    @PutMapping("/{id}")
    public RestaurantTableResponse update(@PathVariable UUID id, @Valid @RequestBody RestaurantTableRequest request,
            Authentication authentication) {
        restaurantAccess.requireOwned(tableService.get(id).restaurantId(), authentication);
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return tableService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        restaurantAccess.requireOwned(tableService.get(id).restaurantId(), authentication);
        tableService.delete(id);
    }
}
