package com.callbot.ai.notification;

import java.util.UUID;

/**
 * The difference was paid, a table was free, and the reservation now seats the larger
 * party.
 *
 * <p>Carries the charge as well as the reservation: the messages that go out name what
 * was collected, which lives on the charge and not on the booking it changed.
 */
public record ReservationTopUpAppliedEvent(UUID reservationId, UUID chargeId) {
}
