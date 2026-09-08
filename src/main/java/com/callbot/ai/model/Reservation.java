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
