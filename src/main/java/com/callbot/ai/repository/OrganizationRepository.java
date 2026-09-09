package com.callbot.ai.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
