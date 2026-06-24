package com.callbot.ai.dto;

import java.util.UUID;

/**
 * Result of ingesting a call. {@code alreadyProcessed} is true when the call
 * (same twilioCallSid) had already been processed: the existing reservation is
 * returned instead of creating a new one (idempotency).
 */
public record CallIngestResponse(
        UUID callId,
        UUID customerId,
        ReservationResponse reservation,
        boolean alreadyProcessed) {
}
