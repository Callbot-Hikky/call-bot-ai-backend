package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.callbot.ai.model.Reservation;
import com.fasterxml.jackson.annotation.JsonInclude;

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
        OffsetDateTime cancelledAt,
        // Objets liés, présents uniquement avec ?expand=table / ?expand=customer.
        // Omis du JSON quand null (donc absents par défaut).
        @JsonInclude(JsonInclude.Include.NON_NULL) RestaurantTableResponse table,
        @JsonInclude(JsonInclude.Include.NON_NULL) CustomerResponse customer) {

    public static ReservationResponse from(Reservation reservation) {
        return from(reservation, null, null);
    }

    public static ReservationResponse from(Reservation reservation,
            RestaurantTableResponse table, CustomerResponse customer) {
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
                reservation.getCancelledAt(),
                table,
                customer);
    }
}
