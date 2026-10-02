package com.callbot.ai.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ce qu'un client voit de sa propre reservation, sans session : rien de plus que ce
 * qu'il a saisi lui-meme. Pas de telephone, pas de notes, pas d'identifiant de table.
 *
 * <p>`paymentToken` n'est rempli que lorsque la table attend un reglement, et il est
 * rendu a celui qui VIENT de reserver : c'est le seul moment ou le navigateur peut
 * l'emmener payer sans attendre son message. Le meme jeton lui part par message de
 * toute facon. Il reste null des que la reservation ne doit rien.
 */
public record PublicReservationResponse(
        UUID id,
        UUID restaurantId,
        String restaurantName,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        Integer partySize,
        String status,
        String customerFirstName,
        String paymentToken) {
}
