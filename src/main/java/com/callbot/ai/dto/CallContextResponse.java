package com.callbot.ai.dto;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Static context fetched once when the call is answered, then cached by the AI
 * for the whole conversation.
 */
public record CallContextResponse(
        Restaurant restaurant,
        List<OpeningHours> hours,
        Map<String, Object> attributes,
        Policies policies) {

    public record Restaurant(
            UUID id,
            String name,
            String phoneNumber,
            String address,
            String city,
            String postalCode,
            String timezone,
            String locale) {
    }

    /** DB convention: dayOfWeek 0 = Monday ... 6 = Sunday. */
    public record OpeningHours(
            Short dayOfWeek,
            String service,
            LocalTime opensAt,
            LocalTime closesAt,
            Boolean isClosed) {
    }

    public record Policies(
            Integer maxPartySize,
            Integer defaultDurationMinutes) {
    }
}
