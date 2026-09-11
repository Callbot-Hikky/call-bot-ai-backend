package com.callbot.ai.dto;

import java.time.OffsetDateTime;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Reservation prise par un client sur la page publique. Le navigateur n'envoie ni fin
 * ni table : le serveur les deduit du creneau propose, seule source de verite.
 */
public record PublicReservationRequest(
        @NotNull OffsetDateTime startsAt,
        @NotNull @Min(1) @Max(15) Integer partySize,
        @NotNull @Valid Customer customer,
        @Size(max = 500) String notes) {

    public record Customer(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Pattern(regexp = "^\\+?[0-9 .()-]{6,20}$") String phone) {
    }
}
