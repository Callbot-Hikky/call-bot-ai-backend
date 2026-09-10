package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.Restaurant;

/**
 * What an account-less diner is shown behind a top-up link.
 *
 * <p>As narrow as {@link PublicReservationResponse}, and for the same reason. It shows
 * both party sizes rather than only the larger one: the diner is buying the difference,
 * and a page that only said "6 couverts" would read as though the change had already
 * happened.
 */
public record PublicTopUpResponse(
        String restaurantName,
        OffsetDateTime startsAt,
        Integer currentPartySize,
        Integer targetPartySize,
        Integer amountCents,
        String currency,
        String status,
        OffsetDateTime expiresAt) {

    public static PublicTopUpResponse of(ReservationCharge charge, Reservation reservation,
            Restaurant restaurant) {
        return new PublicTopUpResponse(
                restaurant.getName(),
                reservation.getStartsAt(),
                reservation.getPartySize(),
                charge.getTargetPartySize(),
                charge.getAmountCents(),
                charge.getCurrency(),
                // Expiry is a deadline passing, not a status the register writes, so the
                // page is told the link is dead rather than left to compare clocks.
                charge.isOpenFor(OffsetDateTime.now()) ? ChargeStatus.PENDING : "closed",
                charge.getTokenExpiresAt());
    }
}
