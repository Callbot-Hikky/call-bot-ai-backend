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
        String guaranteeMode,
        String guaranteeStatus,
        Integer guaranteeAmountCents,
        String currency,
        OffsetDateTime guaranteeExpiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime cancelledAt,
        // Populated only via ?expand=table / ?expand=customer / ?expand=restaurant; omitted otherwise.
        @JsonInclude(JsonInclude.Include.NON_NULL) RestaurantTableResponse table,
        @JsonInclude(JsonInclude.Include.NON_NULL) CustomerResponse customer,
        @JsonInclude(JsonInclude.Include.NON_NULL) RestaurantSummaryResponse restaurant) {

    public static ReservationResponse from(Reservation reservation) {
        return from(reservation, null, null, null);
    }

    public static ReservationResponse from(Reservation reservation,
            RestaurantTableResponse table, CustomerResponse customer,
            RestaurantSummaryResponse restaurant) {
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
                reservation.getGuaranteeMode(),
                reservation.getGuaranteeStatus(),
                reservation.getGuaranteeAmountCents(),
                reservation.getCurrency(),
                reservation.getGuaranteeExpiresAt(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt(),
                reservation.getCancelledAt(),
                table,
                customer,
                restaurant);
    }
}
