package com.callbot.ai.exception;

import org.springframework.dao.DataIntegrityViolationException;

/** Names of the schema constraints the application reacts to, and a way to spot them. */
public final class DatabaseConstraints {

    /** EXCLUDE constraint on reservations: one table, overlapping time ranges. */
    public static final String NO_OVERLAPPING_RESERVATION = "no_overlapping_reservation";
    public static final String UNIQUE_CUSTOMER_PHONE = "uq_customers_restaurant_phone";

    private DatabaseConstraints() {
    }

    /**
     * True when the database reported a violation of the named constraint.
     * PostgreSQL puts the constraint name in the error message, which is the
     * only portable way to tell two integrity violations apart at this layer.
     */
    public static boolean violates(DataIntegrityViolationException ex, String constraintName) {
        String detail = ex.getMostSpecificCause().getMessage();
        return detail != null && detail.toLowerCase().contains(constraintName);
    }
}
