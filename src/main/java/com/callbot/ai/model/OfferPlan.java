package com.callbot.ai.model;

import java.util.Optional;

/**
 * Server-side catalogue of the paid plans. The price is defined here (and never
 * trusted from the client) so a checkout can only ever be created for a known amount.
 */
public enum OfferPlan {

    PRO("pro", "Offre Pro", 9900, "eur", "month");

    private final String code;
    private final String label;
    private final int amountCents;
    private final String currency;
    private final String interval;

    OfferPlan(String code, String label, int amountCents, String currency, String interval) {
        this.code = code;
        this.label = label;
        this.amountCents = amountCents;
        this.currency = currency;
        this.interval = interval;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public int getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public String getInterval() {
        return interval;
    }

    public static Optional<OfferPlan> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (OfferPlan plan : values()) {
            if (plan.code.equalsIgnoreCase(code)) {
                return Optional.of(plan);
            }
        }
        return Optional.empty();
    }
}
