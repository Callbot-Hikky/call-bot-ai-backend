package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.callbot.ai.model.KnowledgeEntry;

public record KnowledgeEntryResponse(
        UUID id,
        String title,
        String content,
        String source,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static KnowledgeEntryResponse from(KnowledgeEntry entry) {
        return new KnowledgeEntryResponse(entry.getId(), entry.getTitle(), entry.getContent(),
                entry.getSource(), entry.getCreatedAt(), entry.getUpdatedAt());
    }
}
