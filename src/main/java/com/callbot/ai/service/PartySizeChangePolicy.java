package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.model.GuaranteeMode;
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
 * {@code top_up_required}. The fee was collected per guest, so the extra guests are owed;
 * collecting that top-up is its own piece of work.
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

        if (GuaranteeMode.fromCode(reservation.getGuaranteeMode()) == GuaranteeMode.BOOKING_FEE) {
            throw new PartySizeChangeRejectedException(
                    PartySizeChangeRejectedException.TOP_UP_REQUIRED,
                    "The booking fee was paid per guest: the extra guests have to be paid for first");
        }
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
