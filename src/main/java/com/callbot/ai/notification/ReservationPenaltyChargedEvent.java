package com.callbot.ai.notification;

import java.util.UUID;

/** The no-show penalty was taken from the diner's registered card. */
public record ReservationPenaltyChargedEvent(UUID reservationId) {
}
