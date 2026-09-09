package com.callbot.ai.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.GuaranteeSettingsRequest;
import com.callbot.ai.dto.GuaranteeSettingsResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.security.CallerOrganizationResolver;

import lombok.RequiredArgsConstructor;

/**
 * Reads and changes what a restaurant asks of its diners.
 *
 * <p>Changing the mode never affects reservations already taken: each one carries the
 * mode in force when it was accepted. Claiming money after the fact for a booking
 * accepted as free would be indefensible, and refunding everyone because the
 * restaurateur went back to free would be just as surprising.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class GuaranteeSettingsService {

    private final RestaurantRepository restaurantRepository;
    private final CallerOrganizationResolver callerOrganization;
    private final ConnectAccountService connectAccount;

    @Transactional(readOnly = true)
    public GuaranteeSettingsResponse get(UUID restaurantId, String callerEmail) {
        return GuaranteeSettingsResponse.from(find(restaurantId, callerEmail));
    }

    public GuaranteeSettingsResponse update(UUID restaurantId, GuaranteeSettingsRequest request,
            String callerEmail) {
        Restaurant restaurant = find(restaurantId, callerEmail);
        GuaranteeMode mode = parseMode(request.mode());

        requireAmountFor(mode, request);
        if (mode.requiresGuarantee()) {
            connectAccount.requireAbleToCharge(restaurant.getOrganizationId());
        }

        restaurant.setGuaranteeMode(mode.code());
        restaurant.setBookingFeeCentsPerGuest(request.bookingFeeCentsPerGuest());
        restaurant.setNoShowPenaltyCentsPerGuest(request.noShowPenaltyCentsPerGuest());
        if (request.refundWindowHours() != null) {
            restaurant.setRefundWindowHours(request.refundWindowHours());
        }
        return GuaranteeSettingsResponse.from(restaurantRepository.save(restaurant));
    }

    private GuaranteeMode parseMode(String code) {
        try {
            return GuaranteeMode.fromCode(code);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException(
                    "Unknown guarantee mode '" + code + "'. Expected one of: none, booking_fee, no_show");
        }
    }

    /**
     * A paying mode without an amount would turn away diners without ever collecting
     * anything — the worst of both worlds, so it is refused outright.
     */
    private void requireAmountFor(GuaranteeMode mode, GuaranteeSettingsRequest request) {
        if (mode == GuaranteeMode.BOOKING_FEE && request.bookingFeeCentsPerGuest() == null) {
            throw new InvalidRequestException("bookingFeeCentsPerGuest is required in booking_fee mode");
        }
        if (mode == GuaranteeMode.NO_SHOW && request.noShowPenaltyCentsPerGuest() == null) {
            throw new InvalidRequestException("noShowPenaltyCentsPerGuest is required in no_show mode");
        }
    }

    private Restaurant find(UUID restaurantId, String callerEmail) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        Optional<UUID> callerOrganizationId = callerOrganization.resolve(callerEmail);
        if (callerOrganizationId.isPresent()
                && !callerOrganizationId.get().equals(restaurant.getOrganizationId())) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
        return restaurant;
    }
}
