package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;

/**
 * What an account-less diner is shown behind their link.
 *
 * <p>Deliberately narrow: no identifiers, no other diner's data, nothing about the
 * restaurant's other reservations. Whoever holds the token sees this reservation and
 * only the facts needed to decide whether to pay.
 */
public record PublicReservationResponse(
        String restaurantName,
        OffsetDateTime startsAt,
        Integer partySize,
        String guaranteeMode,
        String guaranteeStatus,
        String status,
        Integer amountCents,
        String currency,
        Integer refundWindowHours,
        OffsetDateTime expiresAt) {

    public static PublicReservationResponse of(Reservation reservation, Restaurant restaurant) {
        return new PublicReservationResponse(
                restaurant.getName(),
                reservation.getStartsAt(),
                reservation.getPartySize(),
                reservation.getGuaranteeMode(),
                reservation.getGuaranteeStatus(),
                reservation.getStatus(),
                reservation.getGuaranteeAmountCents(),
                reservation.getCurrency(),
                reservation.getGuaranteeRefundWindowHours(),
                reservation.getGuaranteeExpiresAt());
    }
}
