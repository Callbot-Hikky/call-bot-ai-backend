package com.callbot.ai.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import com.callbot.ai.model.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByRestaurantId(UUID restaurantId);

    /**
     * Pre-held reservations whose payment window has closed; their tables must be freed.
     *
     * <p>Rows are locked and already-locked ones skipped, so several application
     * instances can run the sweep at once without expiring the same reservation twice
     * — which would send the diner two "your table is gone" messages.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.status = :status AND r.guaranteeExpiresAt < :deadline
            """)
    List<Reservation> lockExpiredHolds(@Param("status") String status,
            @Param("deadline") OffsetDateTime deadline);

    // Payouts and disputes read the charge register, not the reservation:
    // see ReservationChargeRepository.

    /**
     * Penalties whose cancellation window has closed and which are still unpaid.
     *
     * <p>Locked and skipped rather than queued: two instances must never debit the same
     * diner twice, and a row another instance is already charging is not worth waiting on.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.penaltyDueAt IS NOT NULL
              AND r.penaltyDueAt < :now
              AND r.penaltyAttempts < :maxAttempts
              AND NOT EXISTS (SELECT 1 FROM ReservationCharge c
                              WHERE c.reservationId = r.id
                                AND c.kind = :penaltyKind
                                AND c.status = :paid)
            """)
    List<Reservation> lockDuePenalties(@Param("now") OffsetDateTime now,
            @Param("maxAttempts") int maxAttempts,
            @Param("penaltyKind") String penaltyKind,
            @Param("paid") String paid);

    /** Cards still held for services that are over and can no longer produce a debit. */
    @Query("""
            SELECT r FROM Reservation r
            WHERE r.stripePaymentMethodId IS NOT NULL
              AND r.paymentMethodDetachedAt IS NULL
              AND r.endsAt < :before
              AND (r.penaltyDueAt IS NULL
                   OR r.penaltyAttempts >= :maxAttempts
                   OR EXISTS (SELECT 1 FROM ReservationCharge c
                              WHERE c.reservationId = r.id
                                AND c.kind = :penaltyKind
                                AND c.status = :paid))
            """)
    List<Reservation> findCardsToDetach(@Param("before") OffsetDateTime before,
            @Param("maxAttempts") int maxAttempts,
            @Param("penaltyKind") String penaltyKind,
            @Param("paid") String paid);

    Optional<Reservation> findByPaymentToken(String paymentToken);

    Optional<Reservation> findByCancellationToken(String cancellationToken);

    Optional<Reservation> findByModificationToken(String modificationToken);

    /** Reservations across a set of restaurants — used to scope listings to one organization. */
    List<Reservation> findByRestaurantIdIn(Collection<UUID> restaurantIds);

    List<Reservation> findByCustomerId(UUID customerId);

    Optional<Reservation> findByCallId(UUID callId);

    /** Tables taken over the range, mirroring the {@code no_overlapping_reservation} constraint. */
    @Query("""
            SELECT r.tableId FROM Reservation r
            WHERE r.restaurantId = :restaurantId
              AND r.tableId IS NOT NULL
              AND r.status <> 'cancelled'
              AND r.startsAt < :endsAt
              AND r.endsAt > :startsAt
            """)
    List<UUID> findBusyTableIds(@Param("restaurantId") UUID restaurantId,
            @Param("startsAt") OffsetDateTime startsAt,
            @Param("endsAt") OffsetDateTime endsAt);

    /** Same as {@link #findBusyTableIds}, but skips one reservation (used for reschedule so a resa doesn't block its own slot). */
    @Query("""
            SELECT r.tableId FROM Reservation r
            WHERE r.restaurantId = :restaurantId
              AND r.tableId IS NOT NULL
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
