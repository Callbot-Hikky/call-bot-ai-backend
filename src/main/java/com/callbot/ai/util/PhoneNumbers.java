package com.callbot.ai.util;

/**
 * Un numero de telephone identifie un client dans un restaurant : « 06 12 34 56 78 »,
 * « 06.12.34.56.78 » et « 0612345678 » doivent designer la meme fiche, d'ou qu'ils
 * viennent (assistant vocal, back-office, reservation en ligne).
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** Retire espaces, points, parentheses et tirets ; garde le « + » international. */
    public static String normalize(String raw) {
        return raw == null ? null : raw.replaceAll("[\\s.()-]", "");
    }
}
