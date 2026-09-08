package com.callbot.ai.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.RestaurantMenu;

public interface RestaurantMenuRepository extends JpaRepository<RestaurantMenu, UUID> {
}
