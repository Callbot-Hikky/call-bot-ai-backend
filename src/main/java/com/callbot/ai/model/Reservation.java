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
