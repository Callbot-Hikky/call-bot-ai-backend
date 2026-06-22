package com.callbot.ai.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RestaurantRequest(
        @NotNull UUID organizationId,
        @NotBlank String name,
        @NotBlank String phoneNumber,
        String address,
        String city,
        String postalCode,
        String timezone,
        String locale,
        Boolean isActive) {
}
