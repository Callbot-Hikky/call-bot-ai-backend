package com.callbot.ai.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.FloorPlanRequest;
import com.callbot.ai.dto.FloorPlanResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.FloorPlan;
import com.callbot.ai.repository.FloorPlanRepository;
import com.callbot.ai.repository.RestaurantRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class FloorPlanService {

    private final FloorPlanRepository floorPlanRepository;
    private final RestaurantRepository restaurantRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public FloorPlanResponse get(UUID restaurantId) {
        FloorPlan plan = floorPlanRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("FloorPlan", restaurantId));
        return toResponse(plan);
    }

    /** Creates the plan on first save, replaces the layout afterwards. */
    public FloorPlanResponse upsert(UUID restaurantId, FloorPlanRequest request) {
        requireRestaurant(restaurantId);
        FloorPlan plan = floorPlanRepository.findById(restaurantId)
                .orElseGet(() -> FloorPlan.builder().restaurantId(restaurantId).build());
        plan.setLayout(request.layout().toString());
        return toResponse(floorPlanRepository.save(plan));
    }

    public void delete(UUID restaurantId) {
        if (!floorPlanRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("FloorPlan", restaurantId);
        }
        floorPlanRepository.deleteById(restaurantId);
    }

    private FloorPlanResponse toResponse(FloorPlan plan) {
        return new FloorPlanResponse(
                plan.getRestaurantId(),
                parseLayout(plan.getLayout()),
                plan.getCreatedAt(),
                plan.getUpdatedAt());
    }

    private JsonNode parseLayout(String layout) {
        try {
            return objectMapper.readTree(layout);
        } catch (JacksonException e) {
            // Unreachable in practice: the column is JSONB, Postgres validates it.
            throw new IllegalStateException("Stored floor plan layout is not valid JSON", e);
        }
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }
}
