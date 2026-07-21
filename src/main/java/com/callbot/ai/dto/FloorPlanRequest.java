package com.callbot.ai.dto;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;

/**
 * Upsert payload for a restaurant's floor plan. The layout is the frontend
 * editor's document ({ version, geometry, walls }), stored verbatim.
 */
public record FloorPlanRequest(@NotNull JsonNode layout) {
}
