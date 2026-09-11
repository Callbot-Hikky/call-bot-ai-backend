package com.callbot.ai.model;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
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

    // Table PRINCIPALE (première des tables retenues). Conservée pour les
    // lecteurs mono-table et la contrainte anti-double-booking existante.
    @Column(name = "table_id")
    private UUID tableId;

    // Ensemble des tables occupées par la réservation (principale incluse).
    // Un groupe trop grand pour une seule table est réparti sur plusieurs
    // tables (ex. 15 pers. = 8 + 4 + 4). Persisté dans la table de liaison
    // reservation_tables ; c'est la source de vérité de l'occupation.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "reservation_tables",
            joinColumns = @JoinColumn(name = "reservation_id"))
    @Column(name = "table_id")
    @Builder.Default
    private Set<UUID> tableIds = new LinkedHashSet<>();

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

    /**
     * What one guest costs under this reservation's guarantee, frozen when it was taken.
     *
     * <p>{@link #guaranteeAmountCents} is this multiplied by the party as it then stood.
     * Once a party can grow, the total is no longer the frozen thing — the unit price is,
     * and a top-up is priced off it rather than off the restaurant's current setting.
     */
    @Column(name = "guarantee_cents_per_guest")
    private Integer guaranteeCentsPerGuest;

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

    /**
     * Key letting an account-less diner change their own covers and time.
     *
     * <p>Unlike the payment token, it is <em>not</em> single-use: a party that goes from
     * four to six and then to five walks the same link twice, and burning it on the first
     * pass would mean sending a fresh one after every change.
     */
    @Column(name = "modification_token")
    private String modificationToken;

    /**
     * Hours before the service beyond which the diner may no longer change anything,
     * frozen like the mode and the amount. Zero means up to the service itself.
     */
    @Column(name = "modification_window_hours")
    private Integer modificationWindowHours;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    /**
     * Refund window promised to this diner, in hours, frozen like the mode and the
     * amount. Shortening the restaurant setting must not retract a promise already made.
     */
    @Column(name = "guarantee_refund_window_hours")
    private Integer guaranteeRefundWindowHours;

    // Money collected on this reservation lives in the charge register, one row per
    // movement: see ReservationCharge. A reservation carries none of it, because it can
    // carry several of them — a booking fee, then the top-up a larger party owes.

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

    // Whether the penalty was taken, and under which payment intent, is a charge of kind
    // no_show_penalty in the register. Only the retry state stays here: when the debit
    // becomes due and how many times it has been tried are scheduling, not money.

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
