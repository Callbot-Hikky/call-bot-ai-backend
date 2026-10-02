package com.callbot.ai.embedding;

import org.springframework.http.HttpStatus;

import com.callbot.ai.exception.ApiException;

/** The embedding provider failed or answered with something we cannot store. */
public class EmbeddingException extends ApiException {

    public EmbeddingException(String message) {
        super(HttpStatus.BAD_GATEWAY, "embedding_unavailable", message);
    }
}
