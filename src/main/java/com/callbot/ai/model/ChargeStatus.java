package com.callbot.ai.model;

/** Where one charge stands, mirrored by a CHECK constraint on the table. */
public final class ChargeStatus {

    /** A checkout is open. Nothing has been collected, and nothing is owed to anyone. */
    public static final String PENDING = "pending";

    /** The money was collected. This is the only state a payout ever looks at. */
    public static final String PAID = "paid";

    /** The money went back to the diner, in full. Nothing here will ever be paid out. */
    public static final String REFUNDED = "refunded";

    /**
     * The request ended without a cent ever coming in, and none ever will.
     *
     * <p>Only a top-up reaches this: the window closed, the reservation was cancelled
     * underneath it, or the party was revised down and the amount asked for no longer
     * stood for anything. Which of the three is in the log, not in the status — the
     * register only cares that no money moved.
     *
     * <p>Not {@link #REFUNDED}, which implies a movement in each direction. Reading a
     * lapsed request as a refund would show money going back that never came in.
     */
    public static final String LAPSED = "lapsed";

    private ChargeStatus() {
    }
}
