package com.callbot.ai.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Restaurant;

public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {

    List<Restaurant> findByOrganizationId(UUID organizationId);

    Optional<Restaurant> findByPhoneNumber(String phoneNumber);
}
