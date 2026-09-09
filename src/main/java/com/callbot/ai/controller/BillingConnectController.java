package com.callbot.ai.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.ConnectAccountResponse;
import com.callbot.ai.dto.ConnectOnboardingResponse;
import com.callbot.ai.security.AuthenticatedCaller;
import com.callbot.ai.service.ConnectAccountService;

import lombok.RequiredArgsConstructor;

/** The restaurateur's payment account: its state, and the way to finish setting it up. */
@RestController
@RequestMapping("/api/billing/connect")
@RequiredArgsConstructor
public class BillingConnectController {

    private final ConnectAccountService connectAccount;

    @GetMapping
    public ConnectAccountResponse status(Authentication authentication) {
        return connectAccount.status(AuthenticatedCaller.emailOf(authentication));
    }

    /**
     * Asks Stripe what the account may now do. Called when the restaurateur returns from
     * onboarding, so the screen unlocks without waiting on a webhook.
     */
    @PostMapping("/refresh")
    public ConnectAccountResponse refresh(Authentication authentication) {
        return connectAccount.refresh(AuthenticatedCaller.emailOf(authentication));
    }

    @PostMapping("/onboarding")
    public ConnectOnboardingResponse onboarding(Authentication authentication) {
        return connectAccount.startOnboarding(AuthenticatedCaller.emailOf(authentication));
    }
}
