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
     * Write the new size, and drop the request the old one was priced against.
     *
     * <p>Only a fall reaches this: a rise under a running request is refused outright.
     * The request asked for the difference between the party as it stood and a larger
     * one; move the party underneath it and the amount on that live link stops standing
     * for anything. Better to end it than to leave the diner able to buy a number nobody
     * asked for any more.
     */
    APPLY_AND_LAPSE_TOP_UP,

    /**
     * Leave the reservation exactly as it is and collect the difference first.
     *
     * <p>The party stays at its current size, on its current table, confirmed, until the
     * top-up is settled. Nothing is held for the larger party in the meantime.
     */
    COLLECT_TOP_UP
}
