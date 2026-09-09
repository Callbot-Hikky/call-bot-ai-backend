package com.callbot.ai.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.callbot.ai.model.ReservationCharge;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

/**
 * The register of money movements.
 *
 * <p>Everything the payout sweep, the refund path and the dispute lookup used to read
 * off the reservation is read here instead. A reservation is now only ever the thing a
 * charge points at, never the thing that holds the money.
 */
public interface ReservationChargeRepository extends JpaRepository<ReservationCharge, UUID> {

    List<ReservationCharge> findByReservationId(UUID reservationId);

    List<ReservationCharge> findByReservationIdAndStatus(UUID reservationId, String status);

    Optional<ReservationCharge> findByReservationIdAndKindAndStatus(UUID reservationId,
            String kind, String status);

    boolean existsByReservationIdAndKindAndStatus(UUID reservationId, String kind, String status);

    /** The charge a bank dispute refers to; a dispute carries no metadata of ours. */
    Optional<ReservationCharge> findByStripePaymentIntentId(String stripePaymentIntentId);

    /**
     * Restaurants holding money that is due to leave for their bank.
     *
     * <p>Each restaurant is paid on its own connected account. Refunded charges never
     * appear: that money went back to the diner, so their status is no longer {@code paid}.
     */
    @Query("""
            SELECT DISTINCT r.restaurantId FROM Reservation r
            WHERE EXISTS (SELECT 1 FROM ReservationCharge c
                          WHERE c.reservationId = r.id
                            AND c.status = :paid
                            AND c.paidOutAt IS NULL
                            AND c.payoutEligibleAt < :now)
            """)
    List<UUID> findRestaurantsWithDuePayouts(@Param("paid") String paid,
            @Param("now") OffsetDateTime now);

    /**
     * The charges making up one restaurant's due payout, locked while it is built.
     *
     * <p>Two charges here may well belong to the same reservation — a booking fee and
     * the top-up that followed a larger party. The payout groups money, not tables.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT c FROM ReservationCharge c
            WHERE c.reservationId IN (SELECT r.id FROM Reservation r WHERE r.restaurantId = :restaurantId)
              AND c.status = :paid
              AND c.paidOutAt IS NULL
              AND c.payoutEligibleAt < :now
            """)
    List<ReservationCharge> lockDuePayoutsFor(@Param("restaurantId") UUID restaurantId,
            @Param("paid") String paid, @Param("now") OffsetDateTime now);

    /** The charges a payout claimed, so a refused transfer can release them. */
    List<ReservationCharge> findByPayoutId(UUID payoutId);

    /** Denominator of a restaurant's dispute rate: fees actually taken, penalties aside. */
    @Query("""
            SELECT COUNT(c) FROM ReservationCharge c
            WHERE c.reservationId IN (SELECT r.id FROM Reservation r WHERE r.restaurantId = :restaurantId)
              AND c.kind = :kind
              AND c.paidAt IS NOT NULL
            """)
    long countPaidFor(@Param("restaurantId") UUID restaurantId, @Param("kind") String kind);
}
