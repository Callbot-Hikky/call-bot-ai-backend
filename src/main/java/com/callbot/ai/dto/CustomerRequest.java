package com.callbot.ai.dto;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CustomerRequest(
        @NotNull UUID restaurantId,
        @NotBlank String phone,
        String firstName,
        String lastName,
        @Email String email,
        String notes) {
}
