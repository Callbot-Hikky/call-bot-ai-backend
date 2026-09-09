package com.callbot.ai.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.callbot.ai.dto.MenuFileSummary;
import com.callbot.ai.model.RestaurantMenuFile;

public interface RestaurantMenuFileRepository extends JpaRepository<RestaurantMenuFile, UUID> {

    /** Listage sans la colonne binaire : les octets ne sont charges que pour servir un fichier. */
    List<MenuFileSummary> findSummariesByRestaurantIdOrderByPositionAscCreatedAtAsc(UUID restaurantId);

    Optional<MenuFileSummary> findSummaryByIdAndRestaurantId(UUID id, UUID restaurantId);

    Optional<RestaurantMenuFile> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    long countByRestaurantIdAndKind(UUID restaurantId, String kind);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RestaurantMenuFile f where f.restaurantId = :restaurantId and f.kind = :kind")
    void deleteByRestaurantIdAndKind(@Param("restaurantId") UUID restaurantId, @Param("kind") String kind);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RestaurantMenuFile f where f.id = :id and f.restaurantId = :restaurantId")
    int deleteByIdAndRestaurantId(@Param("id") UUID id, @Param("restaurantId") UUID restaurantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RestaurantMenuFile f set f.position = :position where f.id = :id and f.restaurantId = :restaurantId")
    int updatePosition(@Param("id") UUID id, @Param("restaurantId") UUID restaurantId, @Param("position") int position);
}
