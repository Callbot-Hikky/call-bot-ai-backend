package com.callbot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Content is capped: retrieved entries are injected into a small model's prompt. */
public record KnowledgeEntryRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 2000) String content) {
}
