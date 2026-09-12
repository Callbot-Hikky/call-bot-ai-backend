package com.callbot.ai.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.callbot.ai.dto.ApiError;

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

    /** Erreur metier : le code stable part dans le champ « error », le front s'y fie. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus().value(), ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(HttpStatus.FORBIDDEN.value(), "forbidden",
                        "You do not have access to this restaurant"));
    }


    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(HttpStatus.PAYLOAD_TOO_LARGE.value(), "file_too_large",
                        "File exceeds the maximum upload size"));
    }

    /** A webhook that fails signature verification is a bad request, not a server error. */
    @ExceptionHandler(InvalidPaymentSignatureException.class)
    public ResponseEntity<ApiError> handleInvalidPaymentSignature(InvalidPaymentSignatureException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage()));
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiError> handlePaymentGateway(PaymentGatewayException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of(HttpStatus.BAD_GATEWAY.value(), "Bad Gateway", ex.getMessage()));
    }

    /**
     * Covers unique-key violations and the reservations EXCLUDE constraint
     * (a table double-booked on overlapping time ranges). The constraint name
     * carried by the database cause lets us return a machine-readable code and a
     * precise message instead of one generic conflict.
     */
    @ExceptionHandler({ DataIntegrityViolationException.class, TransactionSystemException.class })
    public ResponseEntity<ApiError> handleDataIntegrity(Exception ex) {
        Throwable cause = ex instanceof DataIntegrityViolationException dive
                ? dive.getMostSpecificCause()
                : rootCause(ex);
        String detail = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();

        String code = "conflict";
        String message = "The request conflicts with an existing resource";
        if (detail.contains("no_overlapping_reservation")) {
            code = "table_overlap";
            message = "This table is already booked for that time slot";
        } else if (detail.contains("uq_customers_restaurant_phone")) {
            code = "duplicate_phone";
            message = "A customer already exists with this phone number";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), code, message));
    }

    /** Un trigger de contrainte differe echoue a la validation : la cause utile est tout au fond. */
    private static Throwable rootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
