package com.callbot.ai.controller;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.ConnectAccountResponse;
import com.callbot.ai.dto.ConnectOnboardingResponse;
import com.callbot.ai.security.AuthenticatedCaller;
import com.callbot.ai.service.ConnectAccountService;

import lombok.RequiredArgsConstructor;

/**
 * A restaurant's payment account: its state, and the way to finish setting it up.
 *
 * <p>Sits under the restaurant, alongside its guarantee settings, because that is where
 * it belongs: the account collects that restaurant's guarantees and pays that
 * restaurant's bank.
 */
@RestController
@RequestMapping("/api/restaurants/{restaurantId}/payment-account")
@RequiredArgsConstructor
public class RestaurantPaymentAccountController {

    private final ConnectAccountService connectAccount;

    @GetMapping
    public ConnectAccountResponse status(@PathVariable UUID restaurantId,
            Authentication authentication) {
        return connectAccount.status(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }

    /**
     * Asks Stripe what the account may now do. Called when the restaurateur returns from
     * onboarding, so the screen unlocks without waiting on a webhook.
     */
    @PostMapping("/refresh")
    public ConnectAccountResponse refresh(@PathVariable UUID restaurantId,
            Authentication authentication) {
        return connectAccount.refresh(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }

    @PostMapping("/onboarding")
    public ConnectOnboardingResponse onboarding(@PathVariable UUID restaurantId,
            Authentication authentication) {
        return connectAccount.startOnboarding(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }
}
