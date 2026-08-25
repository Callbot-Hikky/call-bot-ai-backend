package com.callbot.ai.dto;

import java.util.UUID;

import com.callbot.ai.model.Restaurant;

public record RestaurantSummaryResponse(UUID id, String name) {

    public static RestaurantSummaryResponse from(Restaurant restaurant) {
        return new RestaurantSummaryResponse(restaurant.getId(), restaurant.getName());
    }
}
