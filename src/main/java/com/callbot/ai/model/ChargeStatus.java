package com.callbot.ai.model;

/** Where one charge stands, mirrored by a CHECK constraint on the table. */
public final class ChargeStatus {

    /** A checkout is open. Nothing has been collected, and nothing is owed to anyone. */
    public static final String PENDING = "pending";

    /** The money was collected. This is the only state a payout ever looks at. */
    public static final String PAID = "paid";

    /** The money went back to the diner, in full. Nothing here will ever be paid out. */
    public static final String REFUNDED = "refunded";

    private ChargeStatus() {
    }
}
