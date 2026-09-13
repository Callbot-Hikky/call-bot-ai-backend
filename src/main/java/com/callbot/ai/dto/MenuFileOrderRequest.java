package com.callbot.ai.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

public record MenuFileOrderRequest(@NotEmpty List<UUID> fileIds) {
}
