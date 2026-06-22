package com.callbot.ai.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.CallbackRequest;

public interface CallbackRequestRepository extends JpaRepository<CallbackRequest, UUID> {

    List<CallbackRequest> findByRestaurantId(UUID restaurantId);

    List<CallbackRequest> findByRestaurantIdAndStatus(UUID restaurantId, String status);
}
