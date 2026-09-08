package com.callbot.ai.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.RestaurantMenuFile;

public interface RestaurantMenuFileRepository extends JpaRepository<RestaurantMenuFile, UUID> {

    List<RestaurantMenuFile> findByRestaurantIdOrderByPositionAsc(UUID restaurantId);

    Optional<RestaurantMenuFile> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    long countByRestaurantIdAndKind(UUID restaurantId, String kind);

    void deleteByRestaurantIdAndKind(UUID restaurantId, String kind);
}
