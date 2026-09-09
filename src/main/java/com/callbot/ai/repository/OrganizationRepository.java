package com.callbot.ai.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /** Lookup from a Stripe webhook, which knows the connected account and nothing else. */
    Optional<Organization> findByStripeAccountId(String stripeAccountId);
}
