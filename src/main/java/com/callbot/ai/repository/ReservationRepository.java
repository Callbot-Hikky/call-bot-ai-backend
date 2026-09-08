package com.callbot.ai.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.callbot.ai.model.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByRestaurantId(UUID restaurantId);

    List<Reservation> findByCustomerId(UUID customerId);

    Optional<Reservation> findByCallId(UUID callId);

    /**
     * Toutes les tables occupées sur la plage — y compris celles des
     * réservations réparties sur plusieurs tables (jointure sur la table de
     * liaison reservation_tables via la collection {@code tableIds}).
     */
    @Query("""
            SELECT tid FROM Reservation r JOIN r.tableIds tid
            WHERE r.restaurantId = :restaurantId
              AND r.status <> 'cancelled'
              AND r.startsAt < :endsAt
              AND r.endsAt > :startsAt
            """)
    List<UUID> findBusyTableIds(@Param("restaurantId") UUID restaurantId,
            @Param("startsAt") OffsetDateTime startsAt,
            @Param("endsAt") OffsetDateTime endsAt);

    /** Same as {@link #findBusyTableIds}, but skips one reservation (used for reschedule so a resa doesn't block its own slot). */
    @Query("""
            SELECT tid FROM Reservation r JOIN r.tableIds tid
            WHERE r.restaurantId = :restaurantId
              AND r.status <> 'cancelled'
              AND r.id <> :excludeReservationId
              AND r.startsAt < :endsAt
              AND r.endsAt > :startsAt
            """)
    List<UUID> findBusyTableIdsExcluding(@Param("restaurantId") UUID restaurantId,
            @Param("startsAt") OffsetDateTime startsAt,
            @Param("endsAt") OffsetDateTime endsAt,
            @Param("excludeReservationId") UUID excludeReservationId);
}
