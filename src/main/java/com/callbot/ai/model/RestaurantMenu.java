package com.callbot.ai.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Menu d'un restaurant, un par restaurant. Le contenu saisi a la main est un
 * document JSON appartenant au front, stocke tel quel (JSONB). Les fichiers
 * (PDF, images) sont dans {@link RestaurantMenuFile}.
 */
@Entity
@Table(name = "restaurant_menus")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RestaurantMenu {

    public static final String MODE_NONE = "none";
    public static final String MODE_PDF = "pdf";
    public static final String MODE_IMAGES = "images";
    public static final String MODE_MANUAL = "manual";

    @Id
    @Column(name = "restaurant_id", nullable = false)
    private UUID restaurantId;

    @Column(nullable = false)
    private String mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manual_content", nullable = false, columnDefinition = "jsonb")
    private String manualContent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.mode == null) {
            this.mode = MODE_NONE;
        }
        if (this.manualContent == null) {
            this.manualContent = "{}";
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
