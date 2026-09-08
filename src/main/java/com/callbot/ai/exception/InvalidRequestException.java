package com.callbot.ai.exception;

/**
 * A request the business rules refuse: an unknown guarantee mode, a paying mode with
 * no amount, an exemption asked for by something that is not a person.
 *
 * <p>Distinct from {@link IllegalStateException}, which signals data that should have
 * been impossible and deserves a 500 rather than a 400.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
