package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.callbot.ai.model.UnansweredQuestion;

public record UnansweredQuestionResponse(
        UUID id,
        String question,
        int askedCount,
        OffsetDateTime lastAskedAt,
        String status) {

    public static UnansweredQuestionResponse from(UnansweredQuestion question) {
        return new UnansweredQuestionResponse(question.getId(), question.getQuestion(),
                question.getAskedCount(), question.getLastAskedAt(), question.getStatus());
    }
}
