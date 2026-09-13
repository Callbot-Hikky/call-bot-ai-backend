package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import com.callbot.ai.model.ReservationCharge;

/**
 * What became of a diner's own change to their reservation.
 *
 * <p>{@code startsAt} and {@code partySize} are the reservation as it now stands, which
 * is not always what was asked for: a rise that owes money leaves the party where it was
 * until the difference is settled, and {@code pendingTopUp} is then set to say so. The
 * page must show what is true rather than what was requested.
 *
 * <p>{@code refundedAmountCents} is the answer to the only money question a shrinking
 * party has, and it is stated rather than left to be inferred from a price per cover.
 */
public record GuestModificationResponse(
        OffsetDateTime startsAt,
        Integer partySize,
        int refundedAmountCents,
        PendingTopUpResponse pendingTopUp,
        String topUpPaymentToken) {

    public static GuestModificationResponse applied(OffsetDateTime startsAt, Integer partySize,
            int refundedAmountCents) {
        return new GuestModificationResponse(startsAt, partySize, refundedAmountCents, null, null);
    }

    /**
     * A rise that must be paid for before it counts.
     *
     * <p>The charge's own token travels alongside the amount so the diner can be walked
     * straight to the payment page. It is a fresh single-use link, minted for this rise
     * and nothing else, and the diner is the only one who ever sees it — the dashboard's
     * view of a pending top-up deliberately omits it.
     */
    public static GuestModificationResponse owing(OffsetDateTime startsAt, Integer partySize,
            ReservationCharge topUp) {
        return new GuestModificationResponse(startsAt, partySize, 0,
                PendingTopUpResponse.of(topUp), topUp.getPaymentToken());
    }
}
