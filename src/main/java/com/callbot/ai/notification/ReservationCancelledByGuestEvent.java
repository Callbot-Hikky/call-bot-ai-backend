package com.callbot.ai.notification;

import java.util.UUID;

/**
 * The diner cancelled through their own link.
 *
 * <p>{@code refunded} decides which message goes out, so it travels with the event
 * rather than being recomputed by the listener from a window that may since have moved.
 * {@code refundedAmountCents} travels with it for the same reason: it is the sum of
 * every charge given back, which only the transaction that gave them back can total.
 */
public record ReservationCancelledByGuestEvent(UUID reservationId, boolean refunded,
        int refundedAmountCents) {
}
