package com.callbot.ai.notification;

import java.util.UUID;

/** The payment window closed without the diner acting; the table was released. */
public record ReservationGuaranteeExpiredEvent(UUID reservationId) {
}
