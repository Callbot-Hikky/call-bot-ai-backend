package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReservationRequest(
        @NotNull UUID restaurantId,
        UUID customerId,
        UUID tableId,
        UUID callId,
        @NotNull OffsetDateTime startsAt,
        @NotNull OffsetDateTime endsAt,
        @NotNull @Positive Integer partySize,
        String status,
        String source,
        String notes) {
}
