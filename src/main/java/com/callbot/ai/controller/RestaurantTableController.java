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
import com.callbot.ai.dto.RestaurantTableRequest;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.service.RestaurantTableService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class RestaurantTableController {

    private final RestaurantTableService tableService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantTableResponse create(@Valid @RequestBody RestaurantTableRequest request, Authentication authentication) {
        return tableService.create(request, AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping
    public List<RestaurantTableResponse> list(@RequestParam(required = false) UUID restaurantId,
            Authentication authentication) {
        return tableService.list(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }

    @GetMapping("/{id}")
    public RestaurantTableResponse get(@PathVariable UUID id, Authentication authentication) {
        return tableService.get(id, AuthenticatedCaller.emailOf(authentication));
    }

    @PutMapping("/{id}")
    public RestaurantTableResponse update(@PathVariable UUID id, @Valid @RequestBody RestaurantTableRequest request, Authentication authentication) {
        return tableService.update(id, request, AuthenticatedCaller.emailOf(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        tableService.delete(id, AuthenticatedCaller.emailOf(authentication));
    }
}
