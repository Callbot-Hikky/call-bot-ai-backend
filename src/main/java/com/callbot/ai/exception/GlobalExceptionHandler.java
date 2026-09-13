package com.callbot.ai.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.callbot.ai.dto.ApiError;
import com.callbot.ai.dto.SlotTakenError;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.validation(HttpStatus.BAD_REQUEST.value(), "Bad Request", fieldErrors));
    }

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage()));
    }

    @ExceptionHandler({ BadCredentialsException.class, UsernameNotFoundException.class })
    public ResponseEntity<ApiError> handleBadCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(HttpStatus.UNAUTHORIZED.value(), "Unauthorized", "Invalid email or password"));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage()));
    }

    /**
     * Covers unique-key violations and the reservations EXCLUDE constraint
     * (a table double-booked on overlapping time ranges). The constraint name
     * carried by the database cause lets us return a machine-readable code and a
     * precise message instead of one generic conflict.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        String code = "conflict";
        String message = "The request conflicts with an existing resource";
        if (DatabaseConstraints.violates(ex, DatabaseConstraints.NO_OVERLAPPING_RESERVATION)) {
            code = "table_overlap";
            message = "This table is already booked for that time slot";
        } else if (DatabaseConstraints.violates(ex, DatabaseConstraints.UNIQUE_CUSTOMER_PHONE)) {
            code = "duplicate_phone";
            message = "A customer already exists with this phone number";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), code, message));
    }

    /** Same 409 as a plain overlap, enriched with the slots the AI can offer instead. */
    @ExceptionHandler(SlotTakenException.class)
    public ResponseEntity<SlotTakenError> handleSlotTaken(SlotTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(SlotTakenError.of(ex.getMessage(), ex.getAlternatives()));
    }
}
