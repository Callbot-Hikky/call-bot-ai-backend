package com.callbot.ai.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.repository.ReservationChargeRepository;

import lombok.RequiredArgsConstructor;

/**
 * Decides whether a reservation may change how many people it seats.
 *
 * <p>Until now the number of covers was written as given: a table paid for two could
 * become eight for free, on a table that cannot seat them. Three cases go through one
 * door here.
 *
 * <p><strong>Down</strong> — applied straight away, and the covers given up are handed
 * back when a booking fee paid for them ({@link PartySizeRefund}). Not a cancellation:
 * the diner is still coming, with fewer of them, so the refund window has no say.
 *
 * <p><strong>Up, mode {@code none} or {@code no_show}</strong> — a table check, then
 * applied straight away. Nothing is asked of the diner in {@code no_show}: the penalty
 * is per guest and only ever taken on an absence, so its basis simply follows the covers.
 *
 * <p><strong>Up, mode {@code booking_fee}</strong> — a table check, then the difference
 * is collected before the rise takes effect, when a fee is actually riding on the
 * reservation. The fee was priced per guest, so the extra guests are owed. A fee staff
 * waived, refunded, or never asked for leaves nothing to top up, and the rise goes
 * through like any other.
 *
 * <p>The table check is a finding, not a booking: nothing is held or pre-reserved for the
 * reservation, and the reservation is not moved onto the table that made the change pass.
 * It will therefore be made again when the top-up is settled.
 *
 * <p><strong>Down, under a running request</strong> — applied, and the request dropped
 * with it. It priced the difference against the party as it stood; move that party and
 * the live link asks for a number nobody has requested any more.
 */
@Component
@RequiredArgsConstructor
public class PartySizeChangePolicy {

    private final TableAvailability availability;
    private final ReservationChargeRepository charges;

    /**
     * Weighs a change of party size against the rule.
     *
     * <p>Returns only when the reservation is left in a state the caller can act on; an
     * outright refusal throws, so no caller can write a new size without having passed
     * through here and read the answer.
     *
     * @param reservation    the reservation as stored, still carrying its current party size
     * @param newPartySize   the requested number of covers
     * @param startsAt       the slot the reservation will occupy once saved
     * @param endsAt         end of that same slot
     * @throws PartySizeChangeRejectedException when the change cannot be entertained at all
     */
    public PartySizeChange decide(Reservation reservation, Integer newPartySize,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        Integer current = reservation.getPartySize();
        if (newPartySize == null || current == null || newPartySize == current.intValue()) {
            // Nothing moved, so nothing a running request was priced against moved either.
            return PartySizeChange.APPLY;
        }
        if (newPartySize < current) {
            // Down: no table to check — a smaller party fits wherever the larger one did.
            // The refund is the caller's to make, not a verdict. But a request outstanding
            // was priced against the party that is about to change, so it goes with it.
            return topUpIsRunning(reservation)
                    ? PartySizeChange.APPLY_AND_LAPSE_TOP_UP
                    : PartySizeChange.APPLY;
        }

        requireNoTopUpAlreadyRunning(reservation);
        requireSeatableTable(reservation, newPartySize, startsAt, endsAt);

        if (GuaranteeMode.fromCode(reservation.getGuaranteeMode()) == GuaranteeMode.BOOKING_FEE
                && owesABookingFee(reservation)) {
            return PartySizeChange.COLLECT_TOP_UP;
        }
        return PartySizeChange.APPLY;
    }

    /**
     * One request at a time, per reservation.
     *
     * <p>Two live links would each be payable, for one table: the diner could settle both
     * and someone would have to hand one back by hand. The staff member is told the first
     * request is still running rather than silently replacing it.
     */
    private void requireNoTopUpAlreadyRunning(Reservation reservation) {
        if (topUpIsRunning(reservation)) {
            throw new PartySizeChangeRejectedException(
                    PartySizeChangeRejectedException.TOP_UP_PENDING,
                    "A top-up is already awaiting settlement on this reservation");
        }
    }

    private boolean topUpIsRunning(Reservation reservation) {
        return charges.existsByReservationIdAndKindAndStatus(
                reservation.getId(), ChargeKind.PARTY_SIZE_TOP_UP, ChargeStatus.PENDING);
    }

    /**
     * Whether this reservation actually carries a booking fee that the extra guests would
     * have to be topped up against.
     *
     * <p>The rule follows the money, not the restaurant's setting. A fee staff waived was
     * never collected, so there is nothing to top up and claiming otherwise would leave the
     * party stuck behind a payment of zero. Same for a fee already handed back, or one the
     * restaurant never asked for on this reservation.
     *
     * <p>A fee still {@link GuaranteeStatus#AWAITING awaiting} payment does count: the
     * amount was frozen when the link was sent, so letting the party grow underneath it
     * would quietly undercharge the table.
     */
    private boolean owesABookingFee(Reservation reservation) {
        String status = reservation.getGuaranteeStatus();

        return GuaranteeStatus.AWAITING.equals(status) || GuaranteeStatus.SECURED.equals(status);
    }

    /**
     * A rise to M covers is only accepted if a table that can seat M is free over the slot.
     *
     * <p>A finding, not a booking: nothing is held, and the reservation is not moved onto
     * the table that made the change pass. The same question is put again, in the same
     * place, when the money for the rise lands.
     */
    private void requireSeatableTable(Reservation reservation, int newPartySize,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        boolean seatable = availability.canSeat(reservation.getRestaurantId(), newPartySize,
                startsAt, endsAt, reservation.getId());
        if (!seatable) {
            throw new PartySizeChangeRejectedException(
                    PartySizeChangeRejectedException.NO_TABLE_AVAILABLE,
                    "No table for " + newPartySize + " guests is free on that time slot");
        }
    }
}
