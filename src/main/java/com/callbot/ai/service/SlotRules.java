package com.callbot.ai.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.springframework.http.HttpStatus;

import com.callbot.ai.exception.BookingException;

/** Time rules shared by availability and ingestion: valid range, not in the past, inside the booking window. */
public final class SlotRules {

    public static final String REASON_PAST = "past";
    public static final String REASON_TOO_FAR = "too_far";

    private SlotRules() {
    }

    public static void requireValidRange(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        if (!endsAt.isAfter(startsAt)) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "invalid_range",
                    "endsAt must be after startsAt");
        }
        Duration duration = Duration.between(startsAt, endsAt);
        if (duration.compareTo(BookingPolicy.MIN_DURATION) < 0
                || duration.compareTo(BookingPolicy.MAX_DURATION) > 0) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "invalid_duration",
                    "The slot must last between " + BookingPolicy.MIN_DURATION.toMinutes()
                            + " and " + BookingPolicy.MAX_DURATION.toMinutes() + " minutes");
        }
    }

    /** Refusal reason, or null when the slot can be booked. Days are counted in the restaurant's timezone. */
    public static String refusalReason(ZoneId zone, OffsetDateTime now, OffsetDateTime startsAt) {
        if (startsAt.isBefore(now.minus(BookingPolicy.PAST_TOLERANCE))) {
            return REASON_PAST;
        }
        LocalDate today = now.atZoneSameInstant(zone).toLocalDate();
        LocalDate day = startsAt.atZoneSameInstant(zone).toLocalDate();
        if (day.isAfter(BookingPolicy.lastBookableDay(today))) {
            return REASON_TOO_FAR;
        }
        return null;
    }

    /** Same rule as {@link #refusalReason}, as a 400 for write paths. */
    public static void requireBookable(ZoneId zone, OffsetDateTime now, OffsetDateTime startsAt) {
        String reason = refusalReason(zone, now, startsAt);
        if (REASON_PAST.equals(reason)) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "slot_in_past",
                    "The slot is already in the past");
        }
        if (REASON_TOO_FAR.equals(reason)) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "slot_out_of_window",
                    "The slot must be in the next " + BookingPolicy.WINDOW_DAYS + " days");
        }
    }
}
