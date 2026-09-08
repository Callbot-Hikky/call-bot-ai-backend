package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.callbot.ai.model.Reservation;
import com.fasterxml.jackson.annotation.JsonInclude;

public record ReservationResponse(
        UUID id,
        UUID restaurantId,
        UUID customerId,
        // Table principale (compat mono-table).
        UUID tableId,
        // Toutes les tables de la réservation (groupe réparti sur plusieurs tables).
        List<UUID> tableIds,
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
        // Populated only via ?expand=table / ?expand=customer / ?expand=restaurant; omitted otherwise.
        @JsonInclude(JsonInclude.Include.NON_NULL) RestaurantTableResponse table,
        // Détail de toutes les tables (via ?expand=table) quand le groupe est réparti.
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RestaurantTableResponse> tables,
        @JsonInclude(JsonInclude.Include.NON_NULL) CustomerResponse customer,
        @JsonInclude(JsonInclude.Include.NON_NULL) RestaurantSummaryResponse restaurant) {

    public static ReservationResponse from(Reservation reservation) {
        return from(reservation, null, null, null, null);
    }

    public static ReservationResponse from(Reservation reservation,
            RestaurantTableResponse table, List<RestaurantTableResponse> tables,
            CustomerResponse customer, RestaurantSummaryResponse restaurant) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getRestaurantId(),
                reservation.getCustomerId(),
                reservation.getTableId(),
                reservation.getTableIds() == null ? List.of() : List.copyOf(reservation.getTableIds()),
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
                tables,
                customer,
                restaurant);
    }
}
