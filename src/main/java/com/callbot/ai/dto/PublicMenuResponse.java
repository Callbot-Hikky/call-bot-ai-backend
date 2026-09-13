package com.callbot.ai.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

/** Vue publique : jamais de telephone, d'adresse, d'attributs ni de settings. */
public record PublicMenuResponse(
        String restaurantName,
        String mode,
        JsonNode manual,
        List<MenuFileResponse> files) {
}
