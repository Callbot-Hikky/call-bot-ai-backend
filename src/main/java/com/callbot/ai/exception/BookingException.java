package com.callbot.ai.exception;

import org.springframework.http.HttpStatus;

/** Refus d'une reservation en ligne : creneau hors fenetre, aucune table, couverts hors bornes. */
public class BookingException extends ApiException {

    public BookingException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
