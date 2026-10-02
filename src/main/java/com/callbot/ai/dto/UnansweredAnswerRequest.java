package com.callbot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnansweredAnswerRequest(@NotBlank @Size(max = 2000) String answer) {
}
