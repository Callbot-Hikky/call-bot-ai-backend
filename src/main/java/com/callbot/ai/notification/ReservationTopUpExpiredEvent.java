package com.callbot.ai.notification;

import java.util.UUID;

/**
 * The window closed on a request nobody settled.
 *
 * <p>Only the window. A request ended for another reason — the reservation cancelled
 * underneath it, the party revised down — is not announced this way: those changes carry
 * their own news, and a second message about a link the diner never used would say
 * nothing they need.
 */
public record ReservationTopUpExpiredEvent(UUID reservationId, UUID chargeId) {
}
