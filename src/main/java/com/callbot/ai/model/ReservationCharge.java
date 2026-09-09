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

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** What the restaurateur keeps once Alloquence has taken its share. */
    public int restaurateurShareCents() {
        int fee = applicationFeeCents == null ? 0 : applicationFeeCents;
        return Math.max(0, (amountCents == null ? 0 : amountCents) - fee);
    }

    public boolean isPaid() {
        return ChargeStatus.PAID.equals(status);
    }

    public boolean isBookingFee() {
        return ChargeKind.BOOKING_FEE.equals(kind);
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
