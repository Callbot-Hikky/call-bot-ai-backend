package com.callbot.ai.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Rows are created by an atomic upsert in the repository, never through JPA persist. */
@Entity
@Table(name = "unanswered_questions")
@Getter
@Setter
@NoArgsConstructor
public class UnansweredQuestion {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_ANSWERED = "answered";
    public static final String STATUS_IGNORED = "ignored";

    @Id
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "question_key", nullable = false)
    private String questionKey;

    @Column(name = "asked_count", nullable = false)
    private Integer askedCount;

    @Column(name = "last_asked_at", nullable = false)
    private OffsetDateTime lastAskedAt;

    @Column(nullable = false)
    private String status;

    @Column(name = "answer_entry_id")
    private UUID answerEntryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
