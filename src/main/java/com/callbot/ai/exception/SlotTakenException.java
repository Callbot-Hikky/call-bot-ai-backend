package com.callbot.ai.exception;

import java.util.List;

import com.callbot.ai.dto.AvailabilityResponse.Slot;

/**
 * The requested table was booked by someone else between the availability
 * check and the ingestion. Carries the slots the AI can offer instead.
 */
public class SlotTakenException extends RuntimeException {

    private final transient List<Slot> alternatives;

    public SlotTakenException(List<Slot> alternatives) {
        super("This table is already booked for that time slot");
        this.alternatives = alternatives;
    }

    public List<Slot> getAlternatives() {
        return alternatives;
    }
}
