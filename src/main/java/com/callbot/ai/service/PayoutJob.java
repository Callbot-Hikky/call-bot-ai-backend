package com.callbot.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Runs the payout sweep once a night.
 *
 * <p>Nightly rather than continuous because the trigger is a calendar fact — a day has
 * passed since the service — not an event, and because Stripe payouts are batch
 * operations a restaurateur reads once a day at most.
 */
@Component
@RequiredArgsConstructor
public class PayoutJob {

    private static final Logger log = LoggerFactory.getLogger(PayoutJob.class);

    private final PayoutService payoutService;

    @Scheduled(cron = "${app.payout.cron:0 15 4 * * *}")
    public void payOutDue() {
        int paid = payoutService.payOutEverythingDue();
        if (paid > 0) {
            log.info("Paid out {} organization(s)", paid);
        }
    }
}
