package com.callbot.ai.exception;

import org.springframework.http.HttpStatus;

/**
 * Erreur metier destinee au client HTTP : un statut, un code stable lisible par le
 * front (« no_table », « slot_out_of_window »...) et un message en anglais, comme le
 * reste de l'API. Les sous-classes ne font que nommer le domaine.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
