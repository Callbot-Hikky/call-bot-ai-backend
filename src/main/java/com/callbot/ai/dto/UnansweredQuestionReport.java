package com.callbot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sent by the voice assistant when a caller asked something it could not answer. */
public record UnansweredQuestionReport(
        @NotBlank String restaurantPhone,
        @NotBlank @Size(max = 500) String question) {
}
