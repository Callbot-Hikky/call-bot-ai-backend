package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Payload sent by the AI microservice at the end of a phone call. Captures the
 * full intent ("this caller booked a table during the call") and is processed in
 * a single transaction.
 */
public record CallIngestRequest(
        // Twilio's unique call identifier, used as the idempotency key.
        @NotBlank String twilioCallSid,
        // Number that was called: identifies the restaurant.
        @NotBlank String restaurantPhone,
        // Caller's number (optional).
        String fromNumber,
        @NotNull @Valid Caller customer,
        @NotNull @Valid Booking reservation) {

    /** Caller details, matched against an existing customer by phone number. */
    public record Caller(
            @NotBlank String phone,
            String firstName,
            String lastName,
            String email) {
    }

    /** Details of the reservation requested during the call. */
    public record Booking(
            UUID tableId,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotNull @Positive Integer partySize,
            String notes) {
    }
}
