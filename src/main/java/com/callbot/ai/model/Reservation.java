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

@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "table_id")
    private UUID tableId;

    @Column(name = "call_id")
    private UUID callId;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "party_size", nullable = false)
    private Integer partySize;

    @Builder.Default
    @Column(nullable = false)
    private String status = "pending";

    @Builder.Default
    @Column(nullable = false)
    private String source = "callbot";

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Mode in force when the reservation was taken; never rewritten afterwards. */
    @Builder.Default
    @Column(name = "guarantee_mode", nullable = false)
    private String guaranteeMode = GuaranteeMode.NONE.code();

    @Builder.Default
    @Column(name = "guarantee_status", nullable = false)
    private String guaranteeStatus = GuaranteeStatus.NOT_REQUIRED;

    /** Total owed for this reservation, in cents: per-guest amount times party size. */
    @Column(name = "guarantee_amount_cents")
    private Integer guaranteeAmountCents;

    @Builder.Default
    @Column(name = "currency", nullable = false)
    private String currency = "eur";

    /** Staff member who waived the guarantee, when one did. */
    @Column(name = "guarantee_exempted_by")
    private UUID guaranteeExemptedBy;

    /** End of the payment window: past this instant the table is released. */
    @Column(name = "guarantee_expires_at")
    private OffsetDateTime guaranteeExpiresAt;

    /** Single-use key letting an account-less diner reach their payment page. */
    @Column(name = "payment_token")
    private String paymentToken;

    /** Key letting an account-less diner cancel; lives until the service. */
    @Column(name = "cancellation_token")
    private String cancellationToken;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    /**
     * Refund window promised to this diner, in hours, frozen like the mode and the
     * amount. Shortening the restaurant setting must not retract a promise already made.
     */
    @Column(name = "guarantee_refund_window_hours")
    private Integer guaranteeRefundWindowHours;

    @Column(name = "stripe_session_id")
    private String stripeSessionId;

    @Column(name = "stripe_payment_intent_id")
    private String stripePaymentIntentId;

    /** Alloquence's commission on this booking fee, in cents. Zero on no-show penalties. */
    @Column(name = "application_fee_cents")
    private Integer applicationFeeCents;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    @Column(name = "refunded_amount_cents")
    private Integer refundedAmountCents;

    /** When this money may leave for the restaurateur's bank: a day after the service. */
    @Column(name = "payout_eligible_at")
    private OffsetDateTime payoutEligibleAt;

    @Column(name = "paid_out_at")
    private OffsetDateTime paidOutAt;

    @Column(name = "payout_id")
    private UUID payoutId;

    /** Card registered for a no-show guarantee, held on the restaurant's own Stripe account. */
    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_payment_method_id")
    private String stripePaymentMethodId;

    @Column(name = "stripe_setup_intent_id")
    private String stripeSetupIntentId;

    @Column(name = "payment_method_detached_at")
    private OffsetDateTime paymentMethodDetachedAt;

    /** When staff recorded the absence. Always a person, never the system's silence. */
    @Column(name = "no_show_recorded_at")
    private OffsetDateTime noShowRecordedAt;

    @Column(name = "no_show_recorded_by")
    private UUID noShowRecordedBy;

    /** End of the window in which staff may take the absence back before anyone is charged. */
    @Column(name = "penalty_due_at")
    private OffsetDateTime penaltyDueAt;

    @Builder.Default
    @Column(name = "penalty_attempts", nullable = false)
    private int penaltyAttempts = 0;

    @Column(name = "penalty_charged_at")
    private OffsetDateTime penaltyChargedAt;

    /**
     * Kept apart from {@code stripePaymentIntentId}, which a bank dispute looks up: a
     * dispute over a penalty is not one Alloquence absorbs, having taken no commission.
     */
    @Column(name = "stripe_penalty_intent_id")
    private String stripePenaltyIntentId;

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
