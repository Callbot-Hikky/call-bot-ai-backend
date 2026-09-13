package com.callbot.ai.dto;

import java.time.Instant;
import java.util.List;

import com.callbot.ai.dto.AvailabilityResponse.Slot;

/** 409 body for an ingest whose table got taken meanwhile; same shape as {@link ApiError} plus alternatives. */
public record SlotTakenError(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<Slot> alternatives) {

    public static SlotTakenError of(String message, List<Slot> alternatives) {
        return new SlotTakenError(Instant.now(), 409, "table_overlap", message, alternatives);
    }
}
