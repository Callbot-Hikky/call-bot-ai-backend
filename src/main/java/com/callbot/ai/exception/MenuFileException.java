package com.callbot.ai.exception;

import org.springframework.http.HttpStatus;

/** Refus d'un fichier de menu : type interdit (415), trop gros (413), trop nombreux (409). */
public class MenuFileException extends ApiException {

    public MenuFileException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
