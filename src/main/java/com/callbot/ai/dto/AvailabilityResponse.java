package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Live answer to "do you have a table at this time?", asked during a call. */
public record AvailabilityResponse(
        boolean available,
        // "closed" or "no_table"; null when available.
        String reason,
        UUID tableId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer partySize,
        List<Slot> alternatives) {

    public record Slot(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID tableId,
            Integer capacity) {
    }
}
