package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.callbot.ai.model.RestaurantTable;

public record RestaurantTableResponse(
        UUID id,
        UUID restaurantId,
        String name,
        Integer capacity,
        String zone,
        Boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static RestaurantTableResponse from(RestaurantTable table) {
        return new RestaurantTableResponse(
                table.getId(),
                table.getRestaurantId(),
                table.getName(),
                table.getCapacity(),
                table.getZone(),
                table.getIsActive(),
                table.getCreatedAt(),
                table.getUpdatedAt());
    }
}
