package com.callbot.ai.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.callbot.ai.model.RestaurantTable;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, UUID> {

    List<RestaurantTable> findByRestaurantId(UUID restaurantId);

    @Query("""
        SELECT t FROM RestaurantTable t
        WHERE t.restaurantId = :restaurantId
          AND t.isActive = true
          AND (:partySize IS NULL OR t.capacity >= :partySize)
          AND NOT EXISTS (
            SELECT 1 FROM Reservation r
            WHERE r.tableId = t.id
              AND r.status NOT IN ('cancelled', 'no_show')
              AND r.startsAt < :endsAt
              AND :startsAt < r.endsAt
          )
    """)
    List<RestaurantTable> findAvailable(
        @Param("restaurantId") UUID restaurantId,
        @Param("startsAt") OffsetDateTime startsAt,
        @Param("endsAt") OffsetDateTime endsAt,
        @Param("partySize") Integer partySize
    );
}
