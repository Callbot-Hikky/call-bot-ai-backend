package com.callbot.ai.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Consent;

public interface ConsentRepository extends JpaRepository<Consent, UUID> {

    List<Consent> findByRestaurantId(UUID restaurantId);

    List<Consent> findByCustomerId(UUID customerId);
}
