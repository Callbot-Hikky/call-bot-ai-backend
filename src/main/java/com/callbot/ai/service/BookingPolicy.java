package com.callbot.ai.service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Regles de reservation communes a l'assistant vocal et a la reservation en ligne :
 * une seule source de verite pour le plafond de couverts et la duree d'une table.
 */
public final class BookingPolicy {

    /** Au-dela, la reservation releve d'un echange humain avec le restaurant. */
    public static final int MAX_PARTY_SIZE = 15;

    /** Duree d'occupation d'une table quand le client ne precise pas d'heure de fin. */
    public static final Duration DEFAULT_DURATION = Duration.ofMinutes(90);

    /** Fenetre de creneaux proposee au client, en jours glissants (~1 mois). */
    public static final int WINDOW_DAYS = 30;

    /** Un client ne cumule pas plusieurs reservations actives le meme jour dans le meme restaurant. */
    public static final int MAX_ACTIVE_PER_DAY = 1;

    /** A caller asking at 20:02 for "20:00" is not in the past. */
    public static final Duration PAST_TOLERANCE = Duration.ofMinutes(5);

    public static final Duration MIN_DURATION = Duration.ofMinutes(30);
    public static final Duration MAX_DURATION = Duration.ofHours(4);

    private BookingPolicy() {
    }

    /** Last bookable day, today being day 1 of the window. */
    public static LocalDate lastBookableDay(LocalDate today) {
        return today.plusDays(WINDOW_DAYS - 1L);
    }
}
