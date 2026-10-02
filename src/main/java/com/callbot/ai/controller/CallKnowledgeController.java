package com.callbot.ai.controller;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.KnowledgeSearchResponse;
import com.callbot.ai.dto.UnansweredQuestionReport;
import com.callbot.ai.service.KnowledgeService;
import com.callbot.ai.service.UnansweredQuestionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/** Knowledge endpoints the voice assistant calls while a customer is on the line. */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
@Validated
public class CallKnowledgeController {

    private final KnowledgeService knowledgeService;
    private final UnansweredQuestionService questionService;

    @GetMapping("/knowledge")
    public KnowledgeSearchResponse knowledge(
            @RequestParam @NotBlank String restaurantPhone,
            @RequestParam @NotBlank @Size(max = 500) String question,
            @RequestParam(required = false) @Min(1) @Max(KnowledgeService.MAX_LIMIT) Integer limit) {
        return knowledgeService.searchByPhone(restaurantPhone, question, limit);
    }

    @PostMapping("/unanswered")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unanswered(@Valid @RequestBody UnansweredQuestionReport report) {
        questionService.report(report.restaurantPhone(), report.question());
    }
}
