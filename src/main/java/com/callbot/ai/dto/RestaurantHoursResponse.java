package com.callbot.ai.dto;

import java.time.LocalTime;
import java.util.UUID;

import com.callbot.ai.model.RestaurantHours;

public record RestaurantHoursResponse(
        UUID id,
        UUID restaurantId,
        Short dayOfWeek,
        String service,
        LocalTime opensAt,
        LocalTime closesAt,
        Boolean isClosed) {

    public static RestaurantHoursResponse from(RestaurantHours hours) {
        return new RestaurantHoursResponse(
                hours.getId(),
                hours.getRestaurantId(),
                hours.getDayOfWeek(),
                hours.getService(),
                hours.getOpensAt(),
                hours.getClosesAt(),
                hours.getIsClosed());
    }
}
