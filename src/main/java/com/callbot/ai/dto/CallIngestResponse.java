package com.callbot.ai.dto;

import java.util.UUID;

/** {@code alreadyProcessed} is true when this call had already been ingested. */
public record CallIngestResponse(
        UUID callId,
        UUID customerId,
        ReservationResponse reservation,
        boolean alreadyProcessed) {
}
