package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

import lombok.RequiredArgsConstructor;

/**
 * Whether a party of a given size can be seated over a given slot, and on what.
 *
 * <p>Asked twice about the same rise, and deliberately so. Once when the staff member
 * requests it, to refuse a party no table could ever hold; once again when the money for
 * it lands, because nothing was held in between. The two questions are the same question,
 * so they are asked in the same place — a second implementation would eventually answer
 * differently, and the diner would pay for a table that was never free.
 *
 * <p>The answer is a finding, not a booking. Nothing here writes anything.
 */
@Component
@RequiredArgsConstructor
public class TableAvailability {

    private final RestaurantTableRepository tableRepository;
    private final ReservationRepository reservationRepository;

    /**
     * The first table able to seat {@code partySize} over the slot, or {@code null}.
     *
     * @param excludeReservationId the reservation being changed, which must not count as
     *                             occupying a table against itself: one already big
     *                             enough stays a candidate, and the party simply stays
     *                             where it is
     */
    public RestaurantTable firstSeating(UUID restaurantId, int partySize,
            OffsetDateTime startsAt, OffsetDateTime endsAt, UUID excludeReservationId) {
        return firstSeating(restaurantId, partySize, startsAt, endsAt, excludeReservationId, null);
    }

    /**
     * The same, with a table to keep if it will do.
     *
     * <p>A party that grows from two to five on a table for six should not be walked
     * across the room for nothing. The staff laid the room out around where people are
     * sitting, so a move nobody asked for is a move they have to notice and undo.
     *
     * @param preferredTableId the table the reservation already sits on, returned
     *                         unchanged when it can seat the larger party
     */
    public RestaurantTable firstSeating(UUID restaurantId, int partySize,
            OffsetDateTime startsAt, OffsetDateTime endsAt, UUID excludeReservationId,
            UUID preferredTableId) {
        List<RestaurantTable> candidates = tableRepository.findByRestaurantId(restaurantId).stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsActive()))
                .filter(t -> t.getCapacity() != null && t.getCapacity() >= partySize)
                .toList();
        if (candidates.isEmpty()) {
            return null;
        }

        Set<UUID> busy = new HashSet<>(reservationRepository.findBusyTableIdsExcluding(
                restaurantId, startsAt, endsAt, excludeReservationId));
        List<RestaurantTable> free = candidates.stream()
                .filter(t -> !busy.contains(t.getId()))
                .toList();

        return free.stream()
                .filter(t -> t.getId().equals(preferredTableId))
                .findFirst()
                .orElseGet(() -> free.stream().findFirst().orElse(null));
    }

    /** Whether any table can seat the party over the slot. */
    public boolean canSeat(UUID restaurantId, int partySize, OffsetDateTime startsAt,
            OffsetDateTime endsAt, UUID excludeReservationId) {
        return firstSeating(restaurantId, partySize, startsAt, endsAt, excludeReservationId) != null;
    }
}
