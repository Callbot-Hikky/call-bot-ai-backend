package com.callbot.ai.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.callbot.ai.model.FloorPlan;

public interface FloorPlanRepository extends JpaRepository<FloorPlan, UUID> {
}
