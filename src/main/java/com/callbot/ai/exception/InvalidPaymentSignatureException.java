package com.callbot.ai.exception;

/**
 * Raised when an inbound provider webhook fails signature verification: the request did
 * not come from the payment provider (or the configured secret is wrong).
 */
public class InvalidPaymentSignatureException extends RuntimeException {

    public InvalidPaymentSignatureException(String message) {
        super(message);
    }
}
