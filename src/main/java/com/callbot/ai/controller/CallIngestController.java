package com.callbot.ai.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.CallIngestRequest;
import com.callbot.ai.dto.CallIngestResponse;
import com.callbot.ai.service.CallIngestService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Service-to-service endpoint called by the AI microservice (API-key auth,
 * SERVICE role). See {@link com.callbot.ai.security.ServiceApiKeyFilter}.
 */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallIngestController {

    private final CallIngestService callIngestService;

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public CallIngestResponse ingest(@Valid @RequestBody CallIngestRequest request) {
        return callIngestService.ingest(request);
    }
}
