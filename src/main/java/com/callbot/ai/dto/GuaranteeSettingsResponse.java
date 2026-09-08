package com.callbot.ai.dto;

import com.callbot.ai.model.Restaurant;

public record GuaranteeSettingsResponse(
        String mode,
        Integer bookingFeeCentsPerGuest,
        Integer noShowPenaltyCentsPerGuest,
        Integer refundWindowHours) {

    public static GuaranteeSettingsResponse from(Restaurant restaurant) {
        return new GuaranteeSettingsResponse(
                restaurant.getGuaranteeMode(),
                restaurant.getBookingFeeCentsPerGuest(),
                restaurant.getNoShowPenaltyCentsPerGuest(),
                restaurant.getRefundWindowHours());
    }
}
