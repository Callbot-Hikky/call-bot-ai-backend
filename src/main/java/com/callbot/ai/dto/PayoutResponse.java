package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.callbot.ai.model.Payout;

/** One line of the restaurateur's payout ledger. */
public record PayoutResponse(
        UUID id,
        Integer amountCents,
        String currency,
        Integer reservationCount,
        String status,
        String failureMessage,
        OffsetDateTime createdAt) {

    public static PayoutResponse from(Payout payout) {
        return new PayoutResponse(
                payout.getId(),
                payout.getAmountCents(),
                payout.getCurrency(),
                payout.getReservationCount(),
                payout.getStatus(),
                payout.getFailureMessage(),
                payout.getCreatedAt());
    }
}
