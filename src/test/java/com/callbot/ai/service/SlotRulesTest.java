package com.callbot.ai.service;

import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.callbot.ai.exception.BookingException;

class SlotRulesTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2030-03-01T18:00:00Z");

    @Test
    void refusalReason_past_beyondTolerance() {
        assertThat(SlotRules.refusalReason(PARIS, NOW, NOW.minusMinutes(6))).isEqualTo("past");
    }

    @Test
    void refusalReason_withinTolerance_isAccepted() {
        assertThat(SlotRules.refusalReason(PARIS, NOW, NOW.minusMinutes(4))).isNull();
    }

    @Test
    void refusalReason_windowIsCountedInRestaurantDays_notUtc() {
        LocalDate lastDay = NOW.atZoneSameInstant(PARIS).toLocalDate().plusDays(BookingPolicy.WINDOW_DAYS - 1L);
        // 23:30 Paris on the last bookable day: still inside.
        OffsetDateTime lastDayLateEvening = lastDay.atTime(23, 30).atZone(PARIS).toOffsetDateTime();
        assertThat(SlotRules.refusalReason(PARIS, NOW, lastDayLateEvening)).isNull();
        // 00:30 Paris the next day (still the previous day in UTC): one day too far.
        OffsetDateTime justAfterMidnight = lastDay.plusDays(1).atTime(0, 30).atZone(PARIS).toOffsetDateTime();
        assertThat(SlotRules.refusalReason(PARIS, NOW, justAfterMidnight)).isEqualTo("too_far");
    }

    @Test
    void requireValidRange_rejectsEndBeforeOrEqualStart() {
        assertThatThrownBy(() -> SlotRules.requireValidRange(NOW, NOW))
                .isInstanceOf(BookingException.class).extracting("code").isEqualTo("invalid_range");
    }

    @Test
    void requireValidRange_boundsTheDuration() {
        assertThatThrownBy(() -> SlotRules.requireValidRange(NOW, NOW.plusMinutes(10)))
                .isInstanceOf(BookingException.class).extracting("code").isEqualTo("invalid_duration");
        assertThatThrownBy(() -> SlotRules.requireValidRange(NOW, NOW.plusHours(5)))
                .isInstanceOf(BookingException.class).extracting("code").isEqualTo("invalid_duration");
        assertThatCode(() -> SlotRules.requireValidRange(NOW, NOW.plusMinutes(90))).doesNotThrowAnyException();
    }

    @Test
    void requireBookable_mapsReasonsTo400Codes() {
        assertThatThrownBy(() -> SlotRules.requireBookable(PARIS, NOW, NOW.minusDays(1)))
                .isInstanceOf(BookingException.class).extracting("code").isEqualTo("slot_in_past");
        assertThatThrownBy(() -> SlotRules.requireBookable(PARIS, NOW, NOW.plusDays(BookingPolicy.WINDOW_DAYS + 1L)))
                .isInstanceOf(BookingException.class).extracting("code").isEqualTo("slot_out_of_window");
        assertThatCode(() -> SlotRules.requireBookable(PARIS, NOW, NOW.plusDays(2))).doesNotThrowAnyException();
    }
}
