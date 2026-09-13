package com.callbot.ai.notification;

import java.util.UUID;

/** The diner secured their reservation: the booking fee is in. */
public record ReservationConfirmedEvent(UUID reservationId) {
}
