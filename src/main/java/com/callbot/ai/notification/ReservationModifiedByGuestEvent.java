package com.callbot.ai.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A diner changed their own booking.
 *
 * <p>Carries what it was before, not only what it is now: a restaurant reading "table of
 * four at 21 h" learns nothing it can act on, while "six became four" frees a table in
 * someone's head. The previous values travel on the event because by the time the
 * listener runs they are gone from the row.
 *
 * @param refundedAmountCents what went back to the diner, zero when nothing did
 */
public record ReservationModifiedByGuestEvent(
        UUID reservationId,
        Integer previousPartySize,
        OffsetDateTime previousStartsAt,
        int refundedAmountCents) {
}
