package com.callbot.ai.dto;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Mode publie et contenu saisi a la main (document du front, stocke tel quel). */
public record MenuRequest(
        @NotNull @Pattern(regexp = "none|pdf|images|manual") String mode,
        JsonNode manual) {
}
