package com.callbot.ai.dto;

import java.time.LocalTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RestaurantHoursRequest(
        @NotNull UUID restaurantId,
        @NotNull @Min(0) @Max(6) Short dayOfWeek,
        @NotBlank String service,
        @NotNull LocalTime opensAt,
        @NotNull LocalTime closesAt,
        Boolean isClosed) {
}
