package com.callbot.ai.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Customer;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    List<Customer> findByRestaurantId(UUID restaurantId);

    /** Everything under the restaurants one organization owns. */
    List<Customer> findByRestaurantIdIn(Collection<UUID> restaurantIds);

    Optional<Customer> findByRestaurantIdAndPhone(UUID restaurantId, String phone);
}
