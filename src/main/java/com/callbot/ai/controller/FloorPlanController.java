package com.callbot.ai.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.security.AuthenticatedCaller;
import com.callbot.ai.dto.FloorPlanRequest;
import com.callbot.ai.dto.FloorPlanResponse;
import com.callbot.ai.service.FloorPlanService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/floor-plans")
@RequiredArgsConstructor
public class FloorPlanController {

    private final FloorPlanService floorPlanService;

    @GetMapping("/{restaurantId}")
    public FloorPlanResponse get(@PathVariable UUID restaurantId, Authentication authentication) {
        return floorPlanService.get(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }

    @PutMapping("/{restaurantId}")
    public FloorPlanResponse upsert(@PathVariable UUID restaurantId,
            @Valid @RequestBody FloorPlanRequest request, Authentication authentication) {
        return floorPlanService.upsert(restaurantId, request,
                AuthenticatedCaller.emailOf(authentication));
    }

    @DeleteMapping("/{restaurantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID restaurantId, Authentication authentication) {
        floorPlanService.delete(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }
}
