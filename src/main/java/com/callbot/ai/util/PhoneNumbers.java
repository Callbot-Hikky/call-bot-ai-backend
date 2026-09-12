package com.callbot.ai.util;

/**
 * Un numero de telephone identifie un client dans un restaurant : « 06 12 34 56 78 »,
 * « 06.12.34.56.78 » et « 0612345678 » doivent designer la meme fiche, d'ou qu'ils
 * viennent (assistant vocal, back-office, reservation en ligne).
 */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /**
     * Retire espaces, points, parentheses et tirets, puis met un numero francais sous
     * sa forme internationale : « 06 12 34 56 78 », « 0033612345678 » et « +33 6 12 34 56 78 »
     * designent la meme fiche. Un numero deja international ou etranger est garde tel quel.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[\\s.()-]", "");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        if (digits.startsWith("+33(0)") || digits.startsWith("+330")) {
            digits = "+33" + digits.substring(digits.indexOf('0', 3) + 1);
        }
        if (digits.matches("0[1-9][0-9]{8}")) {
            digits = "+33" + digits.substring(1);
        }
        return digits;
    }
}
