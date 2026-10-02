package com.callbot.ai.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.callbot.ai.model.UnansweredQuestion;

public interface UnansweredQuestionRepository extends JpaRepository<UnansweredQuestion, UUID> {

    List<UnansweredQuestion> findByRestaurantIdAndStatusOrderByAskedCountDescLastAskedAtDesc(
            UUID restaurantId, String status);

    Optional<UnansweredQuestion> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    /**
     * Records the question, or counts it once more if this restaurant already has it.
     * A single statement, so two calls reporting the same question cannot race.
     */
    @Modifying
    @Query(value = """
            INSERT INTO unanswered_questions (restaurant_id, question, question_key)
            VALUES (:restaurantId, :question, :questionKey)
            ON CONFLICT (restaurant_id, question_key)
            DO UPDATE SET asked_count = unanswered_questions.asked_count + 1,
                          last_asked_at = now()
            """, nativeQuery = true)
    void recordOrCount(@Param("restaurantId") UUID restaurantId, @Param("question") String question,
            @Param("questionKey") String questionKey);
}
