package com.callbot.ai.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Payout;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    List<Payout> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
