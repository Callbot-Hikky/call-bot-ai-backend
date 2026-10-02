package com.callbot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeSearchRequest(@NotBlank @Size(max = 500) String question) {
}
