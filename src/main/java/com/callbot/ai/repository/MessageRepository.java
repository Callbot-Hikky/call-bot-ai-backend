package com.callbot.ai.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Message;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByRestaurantId(UUID restaurantId);

    List<Message> findByReservationId(UUID reservationId);
}
