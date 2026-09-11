package com.callbot.ai.dto;

import java.util.UUID;

/** Projection d'un fichier de menu sans son contenu binaire, pour les listages. */
public interface MenuFileSummary {

    UUID getId();

    UUID getRestaurantId();

    String getKind();

    int getPosition();

    String getContentType();

    long getSizeBytes();
}
