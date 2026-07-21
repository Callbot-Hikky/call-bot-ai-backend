package com.callbot.ai.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RestaurantTableRequest(
        @NotNull UUID restaurantId,
        @NotBlank String name,
        @NotNull @Positive @Max(20) Integer capacity,
        String zone,
        Boolean isActive) {
}
