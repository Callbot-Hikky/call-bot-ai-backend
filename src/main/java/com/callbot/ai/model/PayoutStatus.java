package com.callbot.ai.model;

/** Payout states, mirrored by a CHECK constraint on the table. */
public final class PayoutStatus {

    /** Recorded, but Stripe has not accepted it — usually a balance not yet available. */
    public static final String PENDING = "pending";

    /** Stripe accepted the payout; the money is on its way to the bank. */
    public static final String PAID = "paid";

    /** Stripe refused. The reservations stay unpaid-out and are retried. */
    public static final String FAILED = "failed";

    private PayoutStatus() {
    }
}
