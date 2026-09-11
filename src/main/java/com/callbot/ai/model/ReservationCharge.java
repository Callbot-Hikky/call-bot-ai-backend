package com.callbot.ai.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One movement of money on a reservation: what was asked, what came in, what went back
 * out, and where the restaurateur's share ended up.
 *
 * <p>A reservation carries zero, one or several of these. Zero when nothing is owed;
 * one for a booking fee or a no-show penalty; several once a party grows and the
 * difference is collected separately. The singular columns this replaced could only
 * ever hold the first.
 *
 * <p>The register is append-only in spirit: a charge moves {@code pending → paid →
 * refunded} and is never rewritten to mean a different payment. A second payment is a
 * second row.
 */
@Entity
@Table(name = "reservation_charges")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    /** See {@link ChargeKind}. Decides whether Alloquence takes a commission. */
    @Column(nullable = false)
    private String kind;

    /** See {@link ChargeStatus}. */
    @Builder.Default
    @Column(nullable = false)
    private String status = ChargeStatus.PENDING;

    /** What the diner is asked for, in cents. */
    @Column(name = "amount_cents", nullable = false)
    private Integer amountCents;

    /** Alloquence's share of {@link #amountCents}. Zero on a no-show penalty. */
    @Builder.Default
    @Column(name = "application_fee_cents", nullable = false)
    private Integer applicationFeeCents = 0;

    @Builder.Default
    @Column(nullable = false)
    private String currency = "eur";

    /** The hosted checkout opened for this charge, if it went through one. */
    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    /** How a dispute finds its way back to the reservation. */
    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    @Column(name = "refunded_amount_cents")
    private Integer refundedAmountCents;

    /** J+1 after the service: nothing leaves for the restaurateur's bank before then. */
    @Column(name = "payout_eligible_at")
    private OffsetDateTime payoutEligibleAt;

    @Column(name = "paid_out_at")
    private OffsetDateTime paidOutAt;

    /** The payout that claimed this charge, cleared again if Stripe refuses it. */
    @Column(name = "payout_id")
    private UUID payoutId;

    /**
     * The diner's own link to this charge, on a top-up. Single use, and never the token
     * that opened the first payment: one link must not settle the other's debt.
     *
     * <p>A booking fee is reached through the reservation's token instead — it exists
     * before any charge does.
     */
    @Column(name = "payment_token")
    private String paymentToken;

    /** When {@link #paymentToken} stops working. Thirty minutes, on a top-up. */
    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    /**
     * The party size this top-up buys. The reservation stays at its current size until
     * the money is in and a table is confirmed free, so the target lives here.
     */
    @Column(name = "target_party_size")
    private Integer targetPartySize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * What the restaurateur keeps once Alloquence has taken its share, and once anything
     * handed back to the diner has been taken off.
     *
     * <p>Money returned shrinks the share by more than itself: the commission goes back
     * with it, pro rata, so what is left owed falls in the same proportion the payment
     * did. Stripe has already pulled that share out of the connected account by the time
     * this is read, so a payout computed any other way would ask the account for a
     * balance it no longer holds.
     *
     * <p>The division truncates, which strands at most a cent per refunded charge on the
     * platform's side. That is the direction to err in: the other one asks a restaurateur's
     * account for money it does not have.
     */
    public int restaurateurShareCents() {
        int amount = amountCents == null ? 0 : amountCents;
        if (amount <= 0) {
            return 0;
        }
        int fee = applicationFeeCents == null ? 0 : applicationFeeCents;
        int kept = Math.max(0, amount - (refundedAmountCents == null ? 0 : refundedAmountCents));
        return (int) Math.max(0, (long) (amount - fee) * kept / amount);
    }

    public boolean isPaid() {
        return ChargeStatus.PAID.equals(status);
    }

    /**
     * Records that the whole of this charge went back to the diner.
     *
     * <p>Four fields that must move together, and the reason this is one call rather
     * than four at each site: leaving {@code payoutEligibleAt} set would let the sweep
     * pay a restaurateur money that has already been handed back. The Stripe refund
     * itself stays with the caller — only it knows the key to make its retry safe.
     */
    public void markRefundedInFull(OffsetDateTime now) {
        this.status = ChargeStatus.REFUNDED;
        this.refundedAt = now;
        this.refundedAmountCents = this.amountCents;
        this.payoutEligibleAt = null;
    }

    /**
     * Records that a share of this charge went back to the diner.
     *
     * <p>The charge stays {@code paid}: the covers that remain were still sold, and the
     * rest is still owed to the restaurateur. Only {@code refundedAmountCents} moves, and
     * {@link #restaurateurShareCents()} reads it — which is why the payout keeps working
     * without knowing this happened.
     *
     * <p>Refunds accumulate, because a party can fall twice on the one charge that paid
     * for it. Reaching the whole amount closes the charge exactly as a single full refund
     * would: nothing is left, so nothing may be paid out.
     *
     * <p>The Stripe refund itself stays with the caller — only it knows the key that makes
     * its retry safe.
     *
     * @throws IllegalArgumentException if the amount is not positive, or exceeds what is
     *                                  left. Capping silently would leave the register
     *                                  claiming a movement Stripe never made.
     */
    public void refundPartially(OffsetDateTime now, int refundCents) {
        if (refundCents <= 0) {
            throw new IllegalArgumentException("A refund of " + refundCents + " cents is not a refund");
        }
        int left = refundableCents();
        if (refundCents > left) {
            throw new IllegalArgumentException("Refunding " + refundCents
                    + " cents would exceed the " + left + " left on this charge");
        }

        this.refundedAt = now;
        this.refundedAmountCents = (refundedAmountCents == null ? 0 : refundedAmountCents) + refundCents;
        if (this.refundableCents() == 0) {
            this.status = ChargeStatus.REFUNDED;
            this.payoutEligibleAt = null;
        }
    }

    /** What is still available to hand back on this charge. */
    public int refundableCents() {
        return Math.max(0, (amountCents == null ? 0 : amountCents)
                - (refundedAmountCents == null ? 0 : refundedAmountCents));
    }

    public boolean isPending() {
        return ChargeStatus.PENDING.equals(status);
    }

    public boolean isRefunded() {
        return ChargeStatus.REFUNDED.equals(status);
    }

    public boolean isBookingFee() {
        return ChargeKind.BOOKING_FEE.equals(kind);
    }

    public boolean isPartySizeTopUp() {
        return ChargeKind.PARTY_SIZE_TOP_UP.equals(kind);
    }

    /** Whether this top-up can still be settled: still standing, and time left. */
    public boolean isOpenFor(OffsetDateTime now) {
        return isPending()
                && tokenExpiresAt != null
                && tokenExpiresAt.isAfter(now);
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
