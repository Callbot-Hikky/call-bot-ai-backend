package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Live answer to "do you have a table at this time?", asked during a call. */
public record AvailabilityResponse(
        boolean available,
        // "closed", "no_table" or "party_too_large"; null when available.
        String reason,
        // Table principale = première des tables retenues (compat mono-table).
        UUID tableId,
        // Toutes les tables retenues : une seule si elle suffit, plusieurs si le
        // groupe a dû être réparti (ex. 15 pers. = 8 + 4 + 4). Vide si indispo.
        List<UUID> tableIds,
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
