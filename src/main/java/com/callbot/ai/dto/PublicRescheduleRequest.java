package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import com.callbot.ai.service.BookingPolicy;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Nouveau creneau choisi par le client depuis le lien de son message de confirmation. */
public record PublicRescheduleRequest(
        @NotNull OffsetDateTime startsAt,
        @NotNull @Min(1) @Max(BookingPolicy.MAX_PARTY_SIZE) Integer partySize,
        @Size(max = 500) String notes) {
}
