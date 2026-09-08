package com.callbot.ai.model;

/**
 * Where a reservation stands with regard to the guarantee its restaurant demands.
 *
 * <p>Distinct from the reservation status: a reservation can be {@code cancelled}
 * while its guarantee is still {@code SECURED} and awaiting a refund decision.
 *
 * <p>Constants rather than an enum, unlike {@link GuaranteeMode}: these values are only
 * ever assigned and compared, matching how {@code status} and {@code source} are already
 * held on the entity. {@code GuaranteeMode} earns an enum because it is parsed from user
 * input, validated and branched on.
 */
public final class GuaranteeStatus {

    /** The restaurant asks for nothing. */
    public static final String NOT_REQUIRED = "not_required";

    /** The diner has been sent a link and the table is held until it expires. */
    public static final String AWAITING = "awaiting";

    /** Fee paid, or card registered: the reservation is confirmed. */
    public static final String SECURED = "secured";

    /** Staff waived the guarantee; the reservation is confirmed without payment. */
    public static final String EXEMPTED = "exempted";

    /** The payment window closed without the diner acting; the table was released. */
    public static final String EXPIRED = "expired";

    /** The booking fee was returned to the diner. */
    public static final String REFUNDED = "refunded";

    /** The no-show penalty was taken from the registered card. */
    public static final String CHARGED = "charged";

    /** The no-show penalty could not be taken; the restaurant has been told. */
    public static final String CHARGE_FAILED = "charge_failed";

    private GuaranteeStatus() {
    }
}
