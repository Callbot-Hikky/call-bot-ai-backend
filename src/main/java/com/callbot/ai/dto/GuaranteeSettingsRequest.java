package com.callbot.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What a restaurant asks of its diners. Amounts are per guest and in cents, so a
 * table of six owes six times the amount.
 */
public record GuaranteeSettingsRequest(
        @NotBlank String mode,
        @Positive Integer bookingFeeCentsPerGuest,
        @Positive Integer noShowPenaltyCentsPerGuest,
        @PositiveOrZero Integer refundWindowHours) {
}
