package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record FloorPlanResponse(
        UUID restaurantId,
        JsonNode layout,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
