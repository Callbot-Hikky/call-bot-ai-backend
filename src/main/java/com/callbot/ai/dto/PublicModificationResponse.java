package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;

/**
 * What an account-less diner is shown on their own modification page.
 *
 * <p>Narrow like every other public view: this reservation, and only the facts needed to
 * decide what to change it to. {@code centsPerGuest} is here so the page can say what a
 * cover costs — or gives back — <em>before</em> the diner commits to a number. A refund
 * that arrives unannounced reads as badly as one that never comes.
 */
public record PublicModificationResponse(
        String restaurantName,
        OffsetDateTime startsAt,
        Integer partySize,
        String guaranteeMode,
        String guaranteeStatus,
        String status,
        Integer centsPerGuest,
        String currency,
        boolean open,
        OffsetDateTime closesAt) {

    public static PublicModificationResponse of(Reservation reservation, Restaurant restaurant,
            boolean open, OffsetDateTime closesAt) {
        return new PublicModificationResponse(
                restaurant.getName(),
                reservation.getStartsAt(),
                reservation.getPartySize(),
                reservation.getGuaranteeMode(),
                reservation.getGuaranteeStatus(),
                reservation.getStatus(),
                reservation.getGuaranteeCentsPerGuest(),
                reservation.getCurrency(),
                open,
                closesAt);
    }

    /**
     * What a spent link shows: the restaurant, and nothing else.
     *
     * <p>For a reservation that is cancelled or whose service has passed. The link still
     * resolves — saying so is kinder than a blank error, and the diner does hold the
     * token — but a link that has outlived its booking has no business still handing out
     * the hour, the party and the money. The restaurant's name stays so the page can tell
     * them who to call.
     */
    public static PublicModificationResponse spent(Reservation reservation, Restaurant restaurant) {
        return new PublicModificationResponse(restaurant.getName(), null, null, null, null,
                reservation.getStatus(), null, null, false, null);
    }
}
