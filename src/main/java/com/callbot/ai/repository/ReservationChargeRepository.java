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

    /**
     * The top-up a diner reached through their link. The token is the whole of their
     * authorisation, so nothing here is ever looked up by id on their behalf.
     */
    Optional<ReservationCharge> findByPaymentToken(String paymentToken);

    /**
     * The charge a hosted checkout belongs to.
     *
     * <p>How a payment is told apart from another on the same reservation: Stripe's
     * metadata only carries the reservation, which no longer identifies one movement.
     *
     * <p>Locked, because Stripe retries until it gets a 2xx and two deliveries of the
     * same event can land at once. Reading the row unlocked, both would find it awaiting
     * settlement and both would go through with it — one refund (the idempotency key
     * sees to that), but two messages telling the diner what became of their money.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ReservationCharge> findByStripeSessionId(String stripeSessionId);

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

    /**
     * Top-up requests whose settlement window has closed and which nobody paid.
     *
     * <p>Rows are locked and already-locked ones skipped, so several application
     * instances can run the sweep at once without closing the same request twice — which
     * would send the diner two "it fell through" messages.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT c FROM ReservationCharge c
            WHERE c.kind = :kind AND c.status = :status AND c.tokenExpiresAt < :deadline
            """)
    List<ReservationCharge> lockExpiredTopUps(@Param("kind") String kind,
            @Param("status") String status, @Param("deadline") OffsetDateTime deadline);

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
