package com.callbot.ai.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.KnowledgeEntryResponse;
import com.callbot.ai.dto.UnansweredQuestionResponse;
import com.callbot.ai.exception.ApiException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.KnowledgeEntry;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.UnansweredQuestion;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.UnansweredQuestionRepository;

import lombok.RequiredArgsConstructor;

/**
 * The loop that makes the assistant better over time: it reports what it could not
 * answer, the restaurateur answers once, and the answer joins the knowledge base.
 * Callers never write to the knowledge base; only a validated answer does.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UnansweredQuestionService {

    private static final int KEY_MAX_LENGTH = 200;

    private final UnansweredQuestionRepository questionRepository;
    private final RestaurantRepository restaurantRepository;
    private final KnowledgeService knowledgeService;

    public void report(String restaurantPhone, String question) {
        Restaurant restaurant = restaurantRepository.findByPhoneNumber(restaurantPhone)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantPhone));
        String key = normalize(question);
        if (key.isEmpty()) {
            return;
        }
        questionRepository.recordOrCount(restaurant.getId(), question.trim(), key);
    }

    @Transactional(readOnly = true)
    public List<UnansweredQuestionResponse> listOpen(UUID restaurantId) {
        return questionRepository
                .findByRestaurantIdAndStatusOrderByAskedCountDescLastAskedAtDesc(restaurantId,
                        UnansweredQuestion.STATUS_OPEN)
                .stream()
                .map(UnansweredQuestionResponse::from)
                .toList();
    }

    /** The question becomes the entry's title, so the next caller's wording matches it closely. */
    public KnowledgeEntryResponse answer(UUID restaurantId, UUID questionId, String answer) {
        UnansweredQuestion question = findOpen(restaurantId, questionId);
        KnowledgeEntry entry = knowledgeService.create(restaurantId, truncate(question.getQuestion(), 200),
                answer, KnowledgeEntry.SOURCE_UNANSWERED);
        question.setStatus(UnansweredQuestion.STATUS_ANSWERED);
        question.setAnswerEntryId(entry.getId());
        return KnowledgeEntryResponse.from(entry);
    }

    public void ignore(UUID restaurantId, UUID questionId) {
        findOpen(restaurantId, questionId).setStatus(UnansweredQuestion.STATUS_IGNORED);
    }

    private UnansweredQuestion findOpen(UUID restaurantId, UUID questionId) {
        UnansweredQuestion question = questionRepository.findByIdAndRestaurantId(questionId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("UnansweredQuestion", questionId));
        if (!UnansweredQuestion.STATUS_OPEN.equals(question.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "question_already_handled",
                    "This question has already been answered or ignored");
        }
        return question;
    }

    /** Lower case, no accents, no punctuation: "Vous avez un menu enfant ?" == "vous avez un menu enfant". */
    static String normalize(String question) {
        String stripped = Normalizer.normalize(question == null ? "" : question, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return truncate(stripped, KEY_MAX_LENGTH);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
