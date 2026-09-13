package com.callbot.ai.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.callbot.ai.dto.ApiError;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void maxUploadSizeExceeded_maps413WithFileTooLargeCode() {
        ResponseEntity<ApiError> response = handler.handleMaxUpload(new MaxUploadSizeExceededException(10L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().error()).isEqualTo("file_too_large");
    }

    @Test
    void accessDenied_maps403WithFixedMessage() {
        ResponseEntity<ApiError> response = handler.handleAccessDenied(
                new AccessDeniedException("Restaurant 123 does not belong to your organization"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().error()).isEqualTo("forbidden");
        assertThat(response.getBody().message()).doesNotContain("123");
    }

    @Test
    void transactionFailureWithoutKnownConstraint_maps500NotConflict() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiError> response = handler.handleDataIntegrity(
                new TransactionSystemException("commit failed", new RuntimeException("connection reset")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("internal_error");
    }

    @Test
    void transactionFailureOnOverlapTrigger_staysA409TableOverlap() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiError> response = handler.handleDataIntegrity(new TransactionSystemException("commit failed",
                new RuntimeException("ERROR: no_overlapping_reservation: table 4 is busy")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("table_overlap");
    }
}
