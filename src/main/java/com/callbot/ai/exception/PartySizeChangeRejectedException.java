package com.callbot.ai.exception;

/**
 * A requested change in party size cannot be applied as it stands.
 *
 * <p>Carries a machine-readable {@link #getReason() reason} so the dashboard can tell
 * staff <em>why</em> the change was turned down rather than showing a silent failure:
 * a table that cannot seat the party and a fee that still has to be topped up call for
 * two different next moves.
 */
public class PartySizeChangeRejectedException extends RuntimeException {

    /** No active table large enough is free over the reservation's slot. */
    public static final String NO_TABLE_AVAILABLE = "no_table_available";

    /** The booking fee was priced per guest: the extra guests have to be paid for first. */
    public static final String TOP_UP_REQUIRED = "top_up_required";

    private final String reason;

    public PartySizeChangeRejectedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
