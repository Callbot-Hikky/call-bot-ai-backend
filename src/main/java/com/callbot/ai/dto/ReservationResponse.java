package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.callbot.ai.model.Reservation;

public record ReservationResponse(
        UUID id,
        UUID restaurantId,
        UUID customerId,
        UUID tableId,
        UUID callId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer partySize,
        String status,
        String source,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime cancelledAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getRestaurantId(),
                reservation.getCustomerId(),
                reservation.getTableId(),
                reservation.getCallId(),
                reservation.getStartsAt(),
                reservation.getEndsAt(),
                reservation.getPartySize(),
                reservation.getStatus(),
                reservation.getSource(),
                reservation.getNotes(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt(),
                reservation.getCancelledAt());
    }
}
