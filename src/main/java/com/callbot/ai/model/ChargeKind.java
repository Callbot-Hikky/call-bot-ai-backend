package com.callbot.ai.model;

/** What a charge is for, mirrored by a CHECK constraint on the table. */
public final class ChargeKind {

    /** The right to book, paid up front. Alloquence takes a commission on it. */
    public static final String BOOKING_FEE = "booking_fee";

    /**
     * The penalty taken from a registered card after an absence.
     *
     * <p>Carries no commission (decision 29): it compensates a restaurateur for a table
     * lost, and billing a share of that would be charging for someone else's misfortune.
     */
    public static final String NO_SHOW_PENALTY = "no_show_penalty";

    private ChargeKind() {
    }
}
