package com.callbot.ai.model;

/**
 * What Alloquence keeps on a booking fee: 5 % of the amount plus 50 cents.
 *
 * <p>Taken only on booking fees. A no-show penalty compensates a restaurateur for a
 * table lost; billing a share of that would be charging for someone else's misfortune,
 * so {@link #none()} is what the no-show flow uses.
 */
public record Commission(int amountCents) {

    private static final int PERCENT = 5;
    private static final int FIXED_CENTS = 50;

    public Commission {
        if (amountCents < 0) {
            throw new IllegalArgumentException("A commission cannot be negative");
        }
    }

    public static Commission none() {
        return new Commission(0);
    }

    /**
     * @param grossCents what the diner pays
     * @return the platform's share, never more than the gross — a one-cent booking fee
     *         must not leave the restaurateur owing money
     */
    public static Commission on(int grossCents) {
        if (grossCents <= 0) {
            return none();
        }
        // Integer arithmetic, rounded half up: money never goes through a double. Widened
        // to long first — the multiplication overflows an int above about 4,3 M€.
        long percentPart = ((long) grossCents * PERCENT + 50) / 100;
        return new Commission((int) Math.min(grossCents, percentPart + FIXED_CENTS));
    }
}
