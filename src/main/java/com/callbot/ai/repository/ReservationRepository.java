package com.callbot.ai.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.callbot.ai.model.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByRestaurantId(UUID restaurantId);

    List<Reservation> findByRestaurantIdIn(Collection<UUID> restaurantIds);

    /**
     * Verrou transactionnel par restaurant : deux reservations ecrites au meme instant sur des
     * tables secondaires ne se voient pas l'une l'autre sous READ COMMITTED, le trigger differe
     * de V16 ne suffit donc pas. Libere avec la transaction.
     */
    @Query(value = "select pg_advisory_xact_lock(hashtext('reservations:' || cast(:restaurantId as text))) is null", nativeQuery = true)
    boolean lockRestaurant(@Param("restaurantId") UUID restaurantId);

    List<Reservation> findByCustomerId(UUID customerId);

    Optional<Reservation> findByCallId(UUID callId);

    Optional<Reservation> findByPublicToken(UUID publicToken);

    /**
     * Toutes les tables occupées sur la plage — y compris celles des
     * réservations réparties sur plusieurs tables (jointure sur la table de
     * liaison reservation_tables via la collection {@code tableIds}).
     */
    @Query("""
            SELECT tid FROM Reservation r JOIN r.tableIds tid
            WHERE r.restaurantId = :restaurantId
              AND r.status IN ('pending', 'confirmed', 'seated')
              AND r.startsAt < :endsAt
              AND r.endsAt > :startsAt
            """)
    List<UUID> findBusyTableIds(@Param("restaurantId") UUID restaurantId,
            @Param("startsAt") OffsetDateTime startsAt,
            @Param("endsAt") OffsetDateTime endsAt);

    /** Reservations actives d'un client sur une plage : un visiteur ne reserve pas deux fois le meme jour. */
    @Query("""
            SELECT COUNT(r) FROM Reservation r
            WHERE r.restaurantId = :restaurantId
              AND r.customerId = :customerId
              AND r.status IN ('pending', 'confirmed', 'seated')
              AND r.startsAt >= :from
              AND r.startsAt < :to
            """)
    long countActiveByCustomerBetween(@Param("restaurantId") UUID restaurantId,
            @Param("customerId") UUID customerId,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);

    /** Same as {@link #findBusyTableIds}, but skips one reservation (used for reschedule so a resa doesn't block its own slot). */
    @Query("""
            SELECT tid FROM Reservation r JOIN r.tableIds tid
            WHERE r.restaurantId = :restaurantId
              AND r.status IN ('pending', 'confirmed', 'seated')
              AND r.id <> :excludeReservationId
              AND r.startsAt < :endsAt
              AND r.endsAt > :startsAt
            """)
    List<UUID> findBusyTableIdsExcluding(@Param("restaurantId") UUID restaurantId,
            @Param("startsAt") OffsetDateTime startsAt,
            @Param("endsAt") OffsetDateTime endsAt,
            @Param("excludeReservationId") UUID excludeReservationId);
}
