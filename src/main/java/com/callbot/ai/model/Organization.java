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
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** Stripe connected account holding this organization's diner payments. */
    @Column(name = "stripe_account_id")
    private String stripeAccountId;

    /** Stripe lets the account take payments; a paying guarantee mode needs this. */
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

    /** Bank disputes on this organization's booking fees, absorbed by Alloquence. */
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
