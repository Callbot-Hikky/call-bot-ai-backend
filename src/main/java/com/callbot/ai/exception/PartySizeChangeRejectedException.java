package com.callbot.ai.exception;

/**
 * A requested change in party size cannot be applied as it stands.
 *
 * <p>Carries a machine-readable {@link #getReason() reason} so the dashboard can tell
 * staff <em>why</em> the change was turned down rather than showing a silent failure:
 * a table that cannot seat the party and a top-up already awaiting settlement call for
 * two different next moves.
 */
public class PartySizeChangeRejectedException extends RuntimeException {

    /** No active table large enough is free over the reservation's slot. */
    public static final String NO_TABLE_AVAILABLE = "no_table_available";

    /** A top-up request is already running on this reservation, and only one may be. */
    public static final String TOP_UP_PENDING = "top_up_pending";

    private final String reason;

    public PartySizeChangeRejectedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
