package com.callbot.ai.dto;

import java.time.OffsetDateTime;

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
        PendingTopUpResponse pendingTopUp) {
}
