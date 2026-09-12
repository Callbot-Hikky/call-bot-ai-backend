package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ce qu'un client voit de sa propre reservation, sans session : rien de plus que ce
 * qu'il a saisi lui-meme. Pas de telephone, pas de notes, pas d'identifiant de table.
 */
public record PublicReservationResponse(
        UUID id,
        UUID restaurantId,
        String restaurantName,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer partySize,
        String status,
        String customerFirstName) {
}
