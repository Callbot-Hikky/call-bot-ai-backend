package com.callbot.ai.dto;

import java.util.List;
import java.util.UUID;

/** Closest entries first. {@code score} is cosine similarity: 1 = same meaning, 0 = unrelated. */
public record KnowledgeSearchResponse(String question, List<Match> matches) {

    public record Match(UUID id, String title, String content, double score) {
    }
}
