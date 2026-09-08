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
