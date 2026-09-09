package com.callbot.ai.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.PayoutResponse;
import com.callbot.ai.security.AuthenticatedCaller;
import com.callbot.ai.service.PayoutService;

import lombok.RequiredArgsConstructor;

/** The restaurateur's ledger: what has been sent to their bank, and what failed. */
@RestController
@RequestMapping("/api/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutService payoutService;

    @GetMapping
    public List<PayoutResponse> list(Authentication authentication) {
        return payoutService.list(AuthenticatedCaller.emailOf(authentication));
    }
}
