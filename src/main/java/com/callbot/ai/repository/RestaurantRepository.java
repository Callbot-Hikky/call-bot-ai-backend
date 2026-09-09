package com.callbot.ai.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Restaurant;

public interface RestaurantRepository extends JpaRepository<Restaurant, UUID> {

    /** Lookup from a Stripe webhook, which knows the connected account and nothing else. */
    Optional<Restaurant> findByStripeAccountId(String stripeAccountId);

    List<Restaurant> findByOrganizationId(UUID organizationId);

    Optional<Restaurant> findByPhoneNumber(String phoneNumber);
}
