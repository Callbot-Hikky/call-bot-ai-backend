package com.callbot.ai.embedding;

/** The embedding provider answered with something we cannot store. */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }
}
