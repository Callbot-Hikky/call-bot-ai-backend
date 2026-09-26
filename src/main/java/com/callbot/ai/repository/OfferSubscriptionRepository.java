package com.callbot.ai.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.OfferSubscription;

public interface OfferSubscriptionRepository extends JpaRepository<OfferSubscription, UUID> {

    Optional<OfferSubscription> findByCheckoutSessionId(String checkoutSessionId);

    boolean existsByOrganizationIdAndStatus(UUID organizationId, String status);
}
