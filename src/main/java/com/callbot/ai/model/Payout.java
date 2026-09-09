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
 * One transfer of collected booking fees to a restaurateur's bank account.
 *
 * <p>Destination charges put the money on the connected account's Stripe balance at
 * once, but those accounts are created on a manual payout schedule: nothing reaches
 * the bank until Alloquence asks, a day after the service. This row is the trace of
 * that ask — and what the restaurateur's ledger screen reads.
 */
@Entity
@Table(name = "payouts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "stripe_payout_id")
    private String stripePayoutId;

    /** Net of Alloquence's commission: what the restaurateur actually receives. */
    @Column(name = "amount_cents", nullable = false)
    private Integer amountCents;

    @Builder.Default
    @Column(nullable = false)
    private String currency = "eur";

    @Builder.Default
    @Column(name = "reservation_count", nullable = false)
    private Integer reservationCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private String status = PayoutStatus.PENDING;

    @Column(name = "failure_message", columnDefinition = "text")
    private String failureMessage;

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
