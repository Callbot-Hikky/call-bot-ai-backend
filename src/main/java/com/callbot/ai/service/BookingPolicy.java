package com.callbot.ai.service;

import java.time.Duration;

/**
 * Regles de reservation communes a l'assistant vocal et a la reservation en ligne :
 * une seule source de verite pour le plafond de couverts et la duree d'une table.
 */
public final class BookingPolicy {

    /** Au-dela, la reservation releve d'un echange humain avec le restaurant. */
    public static final int MAX_PARTY_SIZE = 15;

    /** Duree d'occupation d'une table quand le client ne precise pas d'heure de fin. */
    public static final Duration DEFAULT_DURATION = Duration.ofMinutes(90);

    /** Fenetre de creneaux proposee au client, en jours glissants. */
    public static final int WINDOW_DAYS = 7;

    /** Un client ne cumule pas plusieurs reservations actives le meme jour dans le meme restaurant. */
    public static final int MAX_ACTIVE_PER_DAY = 1;

    private BookingPolicy() {
    }
}
