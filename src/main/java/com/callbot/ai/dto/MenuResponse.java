package com.callbot.ai.dto;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record MenuResponse(
        UUID restaurantId,
        String mode,
        JsonNode manual,
        List<MenuFileResponse> files,
        MenuLimits limits) {
}
