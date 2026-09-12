package com.callbot.ai.dto;

import java.util.UUID;

public record MenuFileResponse(
        UUID id,
        String kind,
        String contentType,
        int position,
        long sizeBytes,
        String url) {
}
