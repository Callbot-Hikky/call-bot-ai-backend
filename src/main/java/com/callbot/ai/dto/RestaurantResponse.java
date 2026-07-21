package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.callbot.ai.model.Restaurant;

public record RestaurantResponse(
        UUID id,
        UUID organizationId,
        String name,
        String phoneNumber,
        String address,
        String city,
        String postalCode,
        String timezone,
        String locale,
        Boolean isActive,
        Map<String, Object> attributes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static RestaurantResponse from(Restaurant restaurant) {
        return new RestaurantResponse(
                restaurant.getId(),
                restaurant.getOrganizationId(),
                restaurant.getName(),
                restaurant.getPhoneNumber(),
                restaurant.getAddress(),
                restaurant.getCity(),
                restaurant.getPostalCode(),
                restaurant.getTimezone(),
                restaurant.getLocale(),
                restaurant.getIsActive(),
                restaurant.getAttributes(),
                restaurant.getCreatedAt(),
                restaurant.getUpdatedAt());
    }
}
