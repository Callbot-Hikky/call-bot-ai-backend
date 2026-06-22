package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.callbot.ai.model.Customer;

public record CustomerResponse(
        UUID id,
        UUID restaurantId,
        String phone,
        String firstName,
        String lastName,
        String email,
        String notes,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getRestaurantId(),
                customer.getPhone(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.getNotes(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }
}
