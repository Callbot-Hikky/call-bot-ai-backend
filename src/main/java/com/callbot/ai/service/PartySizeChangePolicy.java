package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.ReservationChargeRepository;
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
 * <p><strong>Up, mode {@code booking_fee}</strong> — a table check, then the difference
 * is collected before the rise takes effect, when a fee is actually riding on the
 * reservation. The fee was priced per guest, so the extra guests are owed. A fee staff
 * waived, refunded, or never asked for leaves nothing to top up, and the rise goes
 * through like any other.
 *
 * <p>The table check is a finding, not a booking: nothing is held or pre-reserved for the
 * reservation, and the reservation is not moved onto the table that made the change pass.
 * It will therefore be made again when the top-up is settled.
 */
@Component
@RequiredArgsConstructor
public class PartySizeChangePolicy {

    private final RestaurantTableRepository tableRepository;
    private final ReservationRepository reservationRepository;
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
        if (newPartySize == null || current == null || newPartySize <= current) {
            // Unchanged or down: nothing to check, and nothing to refund.
            return PartySizeChange.APPLY;
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
        boolean pending = charges.existsByReservationIdAndKindAndStatus(
                reservation.getId(), ChargeKind.PARTY_SIZE_TOP_UP, ChargeStatus.PENDING);
        if (pending) {
            throw new PartySizeChangeRejectedException(
                    PartySizeChangeRejectedException.TOP_UP_PENDING,
                    "A top-up is already awaiting settlement on this reservation");
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
