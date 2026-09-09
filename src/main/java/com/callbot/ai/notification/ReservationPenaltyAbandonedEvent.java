package com.callbot.ai.notification;

import java.util.UUID;

/**
 * The penalty could not be taken and will not be tried again.
 *
 * <p>Goes only to the restaurateur. The diner is not told that a debit failed: they were
 * warned when they booked, nothing left their account, and the matter is now between the
 * restaurant and them.
 */
public record ReservationPenaltyAbandonedEvent(UUID reservationId, String reason) {
}
