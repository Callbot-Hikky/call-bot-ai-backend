package com.callbot.ai.notification;

import java.util.UUID;

/**
 * The difference was paid and the room could not take the larger party after all, so
 * the money went straight back.
 *
 * <p>The reservation is untouched — same covers, same table — which is precisely what
 * the diner has to be told: they paid, nothing changed, and they are whole again.
 */
public record ReservationTopUpRefundedEvent(UUID reservationId, UUID chargeId) {
}
