package com.callbot.ai.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.KnowledgeEntryRequest;
import com.callbot.ai.dto.KnowledgeEntryResponse;
import com.callbot.ai.dto.KnowledgeSearchRequest;
import com.callbot.ai.dto.KnowledgeSearchResponse;
import com.callbot.ai.dto.UnansweredAnswerRequest;
import com.callbot.ai.dto.UnansweredQuestionResponse;
import com.callbot.ai.security.RestaurantAccess;
import com.callbot.ai.service.KnowledgeService;
import com.callbot.ai.service.UnansweredQuestionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** What the restaurateur teaches the assistant. Every call checks restaurant ownership. */
@RestController
@RequestMapping("/api/restaurants/{restaurantId}/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final UnansweredQuestionService questionService;
    private final RestaurantAccess restaurantAccess;

    @GetMapping
    public List<KnowledgeEntryResponse> list(@PathVariable UUID restaurantId, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return knowledgeService.list(restaurantId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeEntryResponse create(@PathVariable UUID restaurantId,
            @Valid @RequestBody KnowledgeEntryRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return knowledgeService.create(restaurantId, request);
    }

    @PutMapping("/{entryId}")
    public KnowledgeEntryResponse update(@PathVariable UUID restaurantId, @PathVariable UUID entryId,
            @Valid @RequestBody KnowledgeEntryRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return knowledgeService.update(restaurantId, entryId, request);
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID restaurantId, @PathVariable UUID entryId,
            Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        knowledgeService.delete(restaurantId, entryId);
    }

    /** "Test the assistant": the restaurateur sees exactly what a caller's question would retrieve. */
    @PostMapping("/search")
    public KnowledgeSearchResponse search(@PathVariable UUID restaurantId,
            @Valid @RequestBody KnowledgeSearchRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return knowledgeService.search(restaurantId, request.question(), null);
    }

    @GetMapping("/questions")
    public List<UnansweredQuestionResponse> openQuestions(@PathVariable UUID restaurantId,
            Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return questionService.listOpen(restaurantId);
    }

    @PostMapping("/questions/{questionId}/answer")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeEntryResponse answer(@PathVariable UUID restaurantId, @PathVariable UUID questionId,
            @Valid @RequestBody UnansweredAnswerRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        return questionService.answer(restaurantId, questionId, request.answer());
    }

    @PostMapping("/questions/{questionId}/ignore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ignore(@PathVariable UUID restaurantId, @PathVariable UUID questionId,
            Authentication authentication) {
        restaurantAccess.requireOwned(restaurantId, authentication);
        questionService.ignore(restaurantId, questionId);
    }
}
