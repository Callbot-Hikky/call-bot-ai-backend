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
        String notes,
        /**
         * Staff waiving the guarantee for this reservation — a regular but traced
         * exception (a regular, someone standing at the counter). Ignored when the
         * restaurant asks for no guarantee.
         */
        Boolean exemptGuarantee) {
}
