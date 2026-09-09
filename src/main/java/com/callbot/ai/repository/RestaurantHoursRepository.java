package com.callbot.ai.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.RestaurantHours;

public interface RestaurantHoursRepository extends JpaRepository<RestaurantHours, UUID> {

    List<RestaurantHours> findByRestaurantId(UUID restaurantId);

    /** Everything under the restaurants one organization owns. */
    List<RestaurantHours> findByRestaurantIdIn(List<UUID> restaurantIds);
}
