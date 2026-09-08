package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Payload sent by the AI microservice at the end of a phone call. */
public record CallIngestRequest(
        // Idempotency key.
        @NotBlank String twilioCallSid,
        // Number that was called: identifies the restaurant.
        @NotBlank String restaurantPhone,
        String fromNumber,
        @NotNull @Valid Caller customer,
        @NotNull @Valid Booking reservation) {

    /** Matched against an existing customer by phone number. */
    public record Caller(
            @NotBlank String phone,
            String firstName,
            String lastName,
            String email) {
    }

    public record Booking(
            // Table principale (compat). Peut être null si le groupe est réparti.
            UUID tableId,
            // Toutes les tables retenues (groupe réparti sur plusieurs tables).
            // Si absent/vide, on retombe sur {@code tableId}.
            List<UUID> tableIds,
            @NotNull OffsetDateTime startsAt,
            @NotNull OffsetDateTime endsAt,
            @NotNull @Positive Integer partySize,
            String notes) {
    }
}
