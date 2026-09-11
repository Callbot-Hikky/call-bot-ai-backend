package com.callbot.ai.exception;

import org.springframework.http.HttpStatus;

/** Refus d'un fichier de menu : type interdit (415), trop gros (413), trop nombreux (409). */
public class MenuFileException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MenuFileException(HttpStatus status, String code, String message) {
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
