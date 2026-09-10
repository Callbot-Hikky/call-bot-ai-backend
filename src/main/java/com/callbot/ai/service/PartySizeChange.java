package com.callbot.ai.service;

/**
 * What the rule says should happen to a requested change of party size.
 *
 * <p>Only outcomes that leave the reservation in a usable state appear here. A change
 * the rule turns down outright — no table can seat the party, a top-up is already
 * running — is not a value to inspect but a
 * {@link com.callbot.ai.exception.PartySizeChangeRejectedException}: nothing is written,
 * and the caller has nothing to decide.
 */
public enum PartySizeChange {

    /** Write the new size. Nothing is owed, or nothing was ever collected. */
    APPLY,

    /**
     * Leave the reservation exactly as it is and collect the difference first.
     *
     * <p>The party stays at its current size, on its current table, confirmed, until the
     * top-up is settled. Nothing is held for the larger party in the meantime.
     */
    COLLECT_TOP_UP
}
