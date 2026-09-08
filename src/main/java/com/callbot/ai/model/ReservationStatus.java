package com.callbot.ai.model;

/**
 * Reservation statuses, mirrored by a CHECK constraint on the table.
 *
 * <p>{@link #AWAITING_PAYMENT} is the pre-held state: the table is blocked, but
 * nothing has been promised to the diner yet. It blocks the table exactly like an
 * active reservation, so the diner still has a table when they pay.
 */
public final class ReservationStatus {

    public static final String AWAITING_PAYMENT = "awaiting_payment";
    public static final String PENDING = "pending";
    public static final String CONFIRMED = "confirmed";
    public static final String SEATED = "seated";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";
    public static final String NO_SHOW = "no_show";

    private ReservationStatus() {
    }
}
