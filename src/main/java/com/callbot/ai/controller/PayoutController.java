package com.callbot.ai.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.PayoutResponse;
import com.callbot.ai.security.AuthenticatedCaller;
import com.callbot.ai.service.PayoutService;

import lombok.RequiredArgsConstructor;

/** A restaurant's ledger: what has been sent to its bank, and what failed. */
@RestController
@RequestMapping("/api/restaurants/{restaurantId}/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    @GetMapping
    public List<PayoutResponse> list(@PathVariable UUID restaurantId, Authentication authentication) {
        return payoutService.list(restaurantId, AuthenticatedCaller.emailOf(authentication));
    }
}
