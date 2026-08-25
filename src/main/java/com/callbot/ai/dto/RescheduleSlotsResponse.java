package com.callbot.ai.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Available slots over a rolling 7-day window, for a customer picking a new time. */
public record RescheduleSlotsResponse(List<Day> days) {

    public record Day(LocalDate date, List<Slot> slots) {
    }

    public record Slot(
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            UUID tableId,
            Integer capacity) {
    }
}
