package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import com.callbot.ai.model.ReservationCharge;

/**
 * A top-up the dashboard must show as awaiting settlement, with what is owed and by when.
 *
 * <p>Attached to the reservation the staff member is looking at, because that is where
 * they see the covers that did not move and need the reason why.
 */
public record PendingTopUpResponse(
        Integer targetPartySize,
        Integer amountCents,
        String currency,
        OffsetDateTime expiresAt) {

    public static PendingTopUpResponse of(ReservationCharge charge) {
        return new PendingTopUpResponse(
                charge.getTargetPartySize(),
                charge.getAmountCents(),
                charge.getCurrency(),
                charge.getTokenExpiresAt());
    }
}
