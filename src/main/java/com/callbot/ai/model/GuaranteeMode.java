package com.callbot.ai.model;

import java.util.Arrays;

/**
 * What a restaurant asks of a diner before holding a table.
 *
 * <p>The mode is a restaurant-wide setting, but each reservation freezes the mode
 * in force when it was taken: changing the setting never rewrites reservations
 * already accepted.
 */
public enum GuaranteeMode {

    /** Reservations are free. */
    NONE("none"),

    /**
     * The diner pays to obtain the reservation. The amount is not deducted from the
     * bill — it is a booking fee, neither a deposit nor earnest money.
     */
    BOOKING_FEE("booking_fee"),

    /**
     * The diner pays nothing up front and registers a card; it is only charged if
     * they fail to show up and did not cancel.
     */
    NO_SHOW("no_show");

    private final String code;

    GuaranteeMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Whether the diner must do something before the reservation is confirmed. */
    public boolean requiresGuarantee() {
        return this != NONE;
    }

    public static GuaranteeMode fromCode(String code) {
        return Arrays.stream(values())
                .filter(mode -> mode.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown guarantee mode: " + code));
    }
}
