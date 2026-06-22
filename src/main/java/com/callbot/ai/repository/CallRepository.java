package com.callbot.ai.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Call;

public interface CallRepository extends JpaRepository<Call, UUID> {

    List<Call> findByRestaurantId(UUID restaurantId);

    Optional<Call> findByTwilioCallSid(String twilioCallSid);
}
