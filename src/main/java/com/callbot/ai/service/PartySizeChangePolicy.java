package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

import lombok.RequiredArgsConstructor;

/**
 * Decides whether a reservation may change how many people it seats.
 *
 * <p>Until now the number of covers was written as given: a table paid for two could
 * become eight for free, on a table that cannot seat them. Three cases go through one
 * door here.
 *
 * <p><strong>Down</strong> — applied straight away. No money moves: a partial refund
 * does not exist, so shrinking a party never gives anything back.
 *
 * <p><strong>Up, mode {@code none} or {@code no_show}</strong> — a table check, then
 * applied straight away. Nothing is asked of the diner in {@code no_show}: the penalty
 * is per guest and only ever taken on an absence, so its basis simply follows the covers.
 *
 * <p><strong>Up, mode {@code booking_fee}</strong> — turned down for now with
 * {@code top_up_required} when a fee is actually riding on the reservation. The fee was
 * priced per guest, so the extra guests are owed; collecting that top-up is its own piece
 * of work. A fee staff waived, refunded, or never asked for leaves nothing to top up, and
 * the rise goes through like any other.
 *
 * <p>The table check is a finding, not a booking: nothing is held or pre-reserved for the
 * reservation, and the reservation is not moved onto the table that made the change pass.
 */
@Component
@RequiredArgsConstructor
public class PartySizeChangePolicy {

    private final RestaurantTableRepository tableRepository;
    private final ReservationRepository reservationRepository;

    /**
     * Checks a change of party size against the rule, throwing rather than returning a
     * verdict: every caller writing the new value has to have passed through here.
     *
     * @param reservation    the reservation as stored, still carrying its current party size
     * @param newPartySize   the requested number of covers
     * @param startsAt       the slot the reservation will occupy once saved
     * @param endsAt         end of that same slot
     * @throws PartySizeChangeRejectedException when the change cannot be applied as it stands
     */
    public void check(Reservation reservation, Integer newPartySize,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        Integer current = reservation.getPartySize();
        if (newPartySize == null || current == null || newPartySize <= current) {
            // Unchanged or down: nothing to check, and nothing to refund.
            return;
        }

        requireSeatableTable(reservation, newPartySize, startsAt, endsAt);

        if (GuaranteeMode.fromCode(reservation.getGuaranteeMode()) == GuaranteeMode.BOOKING_FEE
                && owesABookingFee(reservation)) {
            throw new PartySizeChangeRejectedException(
                    PartySizeChangeRejectedException.TOP_UP_REQUIRED,
                    "The booking fee was priced per guest: the extra guests have to be paid for first");
        }
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
     * <p>The reservation's own table does not count as taken by itself: a table already
     * large enough stays a valid candidate, and the party simply stays where it is.
     */
    private void requireSeatableTable(Reservation reservation, int newPartySize,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        List<RestaurantTable> candidates = tableRepository
                .findByRestaurantId(reservation.getRestaurantId()).stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
                .filter(t -> t.getCapacity() != null && t.getCapacity() >= newPartySize)
                .toList();

        if (!candidates.isEmpty()) {
            Set<UUID> busy = new HashSet<>(reservationRepository.findBusyTableIdsExcluding(
                    reservation.getRestaurantId(), startsAt, endsAt, reservation.getId()));
            if (candidates.stream().anyMatch(t -> !busy.contains(t.getId()))) {
                return;
            }
        }
        throw new PartySizeChangeRejectedException(
                PartySizeChangeRejectedException.NO_TABLE_AVAILABLE,
                "No table for " + newPartySize + " guests is free on that time slot");
    }
}
