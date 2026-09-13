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
        // Détail de toutes les tables (via ?expand=table) quand le groupe est réparti.
        @JsonInclude(JsonInclude.Include.NON_NULL) List<RestaurantTableResponse> tables,
        @JsonInclude(JsonInclude.Include.NON_NULL) CustomerResponse customer,
        @JsonInclude(JsonInclude.Include.NON_NULL) RestaurantSummaryResponse restaurant,
        // Present only while a rise in covers is waiting to be paid for.
        @JsonInclude(JsonInclude.Include.NON_NULL) PendingTopUpResponse pendingTopUp) {

    public static ReservationResponse from(Reservation reservation) {
        return from(reservation, null, null, null, null, null);
    }

    public static ReservationResponse from(Reservation reservation,
            PendingTopUpResponse pendingTopUp) {
        return from(reservation, null, null, null, null, pendingTopUp);
    }

    public static ReservationResponse from(Reservation reservation,
            RestaurantTableResponse table, CustomerResponse customer,
            RestaurantSummaryResponse restaurant) {
        return from(reservation, table, null, customer, restaurant, null);
    }

    public static ReservationResponse from(Reservation reservation,
            RestaurantTableResponse table, List<RestaurantTableResponse> tables,
            CustomerResponse customer, RestaurantSummaryResponse restaurant) {
        return from(reservation, table, tables, customer, restaurant, null);
    }

    public static ReservationResponse from(Reservation reservation,
            RestaurantTableResponse table, List<RestaurantTableResponse> tables,
            CustomerResponse customer, RestaurantSummaryResponse restaurant,
            PendingTopUpResponse pendingTopUp) {
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
                reservation.getGuaranteeMode(),
                reservation.getGuaranteeStatus(),
                reservation.getGuaranteeAmountCents(),
                reservation.getCurrency(),
                reservation.getGuaranteeExpiresAt(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt(),
                reservation.getCancelledAt(),
                table,
                tables,
                customer,
                restaurant,
                pendingTopUp);
    }
}
