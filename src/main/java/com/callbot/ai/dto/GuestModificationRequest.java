package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Positive;

/**
 * What a diner asks to change about their own reservation.
 *
 * <p>Both fields are optional and either may stand alone: a party that grew without
 * moving, a time that moved without growing, or both at once. What is left out is left
 * alone — this is not a full replacement of the reservation, and a diner must not be able
 * to blank a note or a table by omitting it.
 *
 * <p>The end of the slot is deliberately absent: it is the start plus however long the
 * reservation already ran for. Letting a diner set their own sitting length would let
 * them take the room for the evening.
 */
public record GuestModificationRequest(
        @Positive Integer partySize,
        OffsetDateTime startsAt) {
}
