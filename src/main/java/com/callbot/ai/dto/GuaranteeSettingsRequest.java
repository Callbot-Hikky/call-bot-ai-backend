package com.callbot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What a restaurant asks of its diners. Amounts are per guest and in cents, so a
 * table of six owes six times the amount.
 *
 * <p>The two windows are hours before the service and are independent of each other:
 * one closes refunds, the other closes the diner's own hand on their covers and hour.
 * Zero means up to the service itself, never "never".
 */
public record GuaranteeSettingsRequest(
        @NotBlank String mode,
        @Positive Integer bookingFeeCentsPerGuest,
        @Positive Integer noShowPenaltyCentsPerGuest,
        @PositiveOrZero Integer refundWindowHours,
        @PositiveOrZero Integer modificationWindowHours) {
}
