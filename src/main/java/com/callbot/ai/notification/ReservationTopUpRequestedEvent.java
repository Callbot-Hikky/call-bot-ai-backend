package com.callbot.ai.notification;

import java.util.UUID;

/**
 * A larger party was asked for, and the difference is now owed.
 *
 * <p>Carries the charge as well as the reservation: the message the diner receives is
 * about the top-up — its amount, its link, its deadline — none of which the reservation
 * holds, and a listener re-reading "the pending top-up" could pick up a later one.
 */
public record ReservationTopUpRequestedEvent(UUID reservationId, UUID chargeId) {
}
