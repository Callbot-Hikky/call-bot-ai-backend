package com.callbot.ai.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.CheckoutSessionResponse;
import com.callbot.ai.dto.CheckoutSummaryResponse;
import com.callbot.ai.dto.OfferResponse;
import com.callbot.ai.service.OfferService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
public class OfferController {

    private final OfferService offerService;

    @GetMapping
    public List<OfferResponse> list() {
        return offerService.listOffers();
    }

    /**
     * Subscribes the authenticated user to the given offer and returns the hosted
     * checkout url to redirect the browser to. e.g. {@code POST /api/offers/pro/checkout}.
     */
    @PostMapping("/{code}/checkout")
    public CheckoutSessionResponse checkout(@PathVariable String code,
            @AuthenticationPrincipal UserDetails principal) {
        return offerService.checkout(code, principal.getUsername());
    }

    /** Recap of a checkout session, for the success page (after the provider redirects back). */
    @GetMapping("/checkout/{sessionId}")
    public CheckoutSummaryResponse checkoutSummary(@PathVariable String sessionId,
            @AuthenticationPrincipal UserDetails principal) {
        return offerService.getCheckoutSummary(sessionId, principal.getUsername());
    }
}
