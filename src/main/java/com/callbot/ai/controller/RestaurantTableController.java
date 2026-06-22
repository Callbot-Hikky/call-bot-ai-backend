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
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class RestaurantTableController {

    private final RestaurantTableService tableService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantTableResponse create(@Valid @RequestBody RestaurantTableRequest request) {
        return tableService.create(request);
    }

    @GetMapping
    public List<RestaurantTableResponse> list(@RequestParam(required = false) UUID restaurantId) {
        return tableService.list(restaurantId);
    }

    @GetMapping("/{id}")
    public RestaurantTableResponse get(@PathVariable UUID id) {
        return tableService.get(id);
    }

    @PutMapping("/{id}")
    public RestaurantTableResponse update(@PathVariable UUID id, @Valid @RequestBody RestaurantTableRequest request) {
        return tableService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        tableService.delete(id);
    }
}
