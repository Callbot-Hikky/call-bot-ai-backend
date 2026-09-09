package com.callbot.ai.model;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Table(name = "restaurants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Restaurant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    private String address;

    private String city;

    @Column(name = "postal_code")
    private String postalCode;

    @Builder.Default
    @Column(nullable = false)
    private String timezone = "Europe/Paris";

    @Builder.Default
    @Column(nullable = false)
    private String locale = "fr";

    /** Which guarantee the restaurant asks of its diners. See {@link GuaranteeMode}. */
    @Builder.Default
    @Column(name = "guarantee_mode", nullable = false)
    private String guaranteeMode = GuaranteeMode.NONE.code();

    /** Booking fee charged per guest, in cents. Required in {@code booking_fee} mode. */
    @Column(name = "booking_fee_cents_per_guest")
    private Integer bookingFeeCentsPerGuest;

    /** No-show penalty per guest, in cents. Required in {@code no_show} mode. */
    @Column(name = "no_show_penalty_cents_per_guest")
    private Integer noShowPenaltyCentsPerGuest;

    /** Hours before the service up to which a booking fee is fully refunded. */
    @Builder.Default
    @Column(name = "refund_window_hours", nullable = false)
    private Integer refundWindowHours = 48;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    /** Free-form specifics (halal, terrace...) read by the AI, so no schema change per attribute. */
    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new HashMap<>();

    /**
     * Stripe connected account collecting this restaurant's guarantees.
     *
     * <p>Held per restaurant, not per organization: a Stripe account is tied to a legal
     * entity and a bank account, and two establishments of one owner are often two
     * companies. Sharing one would send the second's money to the first's bank.
     */
    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    /** Stripe lets this account take payments; a paying guarantee mode needs it. */
    @Builder.Default
    @Column(name = "stripe_charges_enabled", nullable = false)
    private boolean stripeChargesEnabled = false;

    @Builder.Default
    @Column(name = "stripe_payouts_enabled", nullable = false)
    private boolean stripePayoutsEnabled = false;

    @Builder.Default
    @Column(name = "stripe_details_submitted", nullable = false)
    private boolean stripeDetailsSubmitted = false;

    @Column(name = "stripe_onboarded_at")
    private OffsetDateTime stripeOnboardedAt;

    /** Bank disputes on this restaurant's booking fees, absorbed by Alloquence. */
    @Builder.Default
    @Column(name = "stripe_dispute_count", nullable = false)
    private int stripeDisputeCount = 0;

    @Column(name = "stripe_last_dispute_at")
    private OffsetDateTime stripeLastDisputeAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

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
