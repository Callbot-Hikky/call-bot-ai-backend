package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Payout;

import lombok.RequiredArgsConstructor;

/**
 * The nightly sweep that sends collected booking fees to the restaurateurs' banks.
 *
 * <p>Nightly rather than continuous because the trigger is a calendar fact — a day has
 * passed since the service — not an event.
 *
 * <p>Each payout runs in three steps:
 *
 * <ol>
 * <li><b>claim</b> — lock the due reservations, stamp them, commit. They are now spoken
 * for, so a second instance cannot pay them again.
 * <li><b>send</b> — call Stripe outside any transaction, keyed on the payout's own id,
 * so a retry that lost Stripe's answer is a no-op there rather than a second transfer.
 * <li><b>settle</b> — record the outcome; on refusal, release the reservations so the
 * next sweep tries again.
 * </ol>
 *
 * <p>The orchestration lives here, and the transactions in {@link PayoutService}, so
 * that each step is a real call through the proxy.
 */
@Component
@RequiredArgsConstructor
public class PayoutJob {

    private static final Logger log = LoggerFactory.getLogger(PayoutJob.class);

    private final PayoutService payouts;
    private final StripeConnectGateway connect;

    @Scheduled(cron = "${app.payout.cron:0 15 4 * * *}")
    public void payOutDue() {
        OffsetDateTime now = OffsetDateTime.now();
        int sent = 0;
        for (UUID organizationId : payouts.organizationsWithMoneyDue(now)) {
            try {
                sent += payOut(organizationId, now);
            } catch (RuntimeException e) {
                // One organization's problem is not the other organizations' problem.
                log.error("Payout sweep failed for organization {}", organizationId, e);
            }
        }
        if (sent > 0) {
            log.info("Sent {} payout(s)", sent);
        }
    }

    /** @return how many transfers Stripe accepted for this organization */
    int payOut(UUID organizationId, OffsetDateTime now) {
        Organization organization = payouts.payableOrganization(organizationId).orElse(null);
        if (organization == null) {
            log.warn("Organization {} has money due but no account able to receive it", organizationId);
            return 0;
        }

        int sent = 0;
        for (Payout claimed : payouts.claim(organizationId, now)) {
            if (send(organization, claimed)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(Organization organization, Payout payout) {
        try {
            String stripePayoutId = connect.payOut(
                    organization.getStripeAccountId(),
                    payout.getAmountCents(),
                    payout.getCurrency(),
                    payout.getId().toString());
            payouts.settle(payout.getId(), stripePayoutId, null);
            return true;
        } catch (PaymentGatewayException e) {
            // Usually a balance Stripe has not made available yet. Settling with a
            // failure is what releases the reservations for the next sweep.
            log.warn("Payout {} of {} {} to organization {} failed: {}",
                    payout.getId(), payout.getAmountCents(), payout.getCurrency(),
                    organization.getId(), e.getMessage());
            payouts.settle(payout.getId(), null, e.getMessage());
            return false;
        }
    }
}
