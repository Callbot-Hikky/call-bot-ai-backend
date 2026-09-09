package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.stripe.NoShowCharge;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Reservation;

import lombok.RequiredArgsConstructor;

/**
 * Charges the no-show penalties whose cancellation window has closed, and forgets the
 * cards of services long past.
 *
 * <p>Runs often enough that a diner is not left waiting hours to learn they were charged,
 * and rarely enough that a bank refusal is not retried in a tight loop.
 */
@Component
@RequiredArgsConstructor
public class NoShowPenaltyJob {

    private static final Logger log = LoggerFactory.getLogger(NoShowPenaltyJob.class);

    private final NoShowPenaltyService penalties;
    private final StripeConnectGateway connect;

    @Scheduled(fixedDelayString = "${app.no-show.charge-scan-interval-ms:600000}")
    public void chargeDuePenalties() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Reservation> due = penalties.claimDuePenalties(now);
        for (Reservation reservation : due) {
            try {
                charge(reservation);
            } catch (RuntimeException e) {
                // One diner's card is not the other diners' problem.
                log.error("Penalty run failed for reservation {}", reservation.getId(), e);
            }
        }
    }

    @Scheduled(cron = "${app.no-show.detach-cron:0 45 4 * * *}")
    public void forgetCardsOfPastServices() {
        for (Reservation reservation : penalties.cardsToForget(OffsetDateTime.now())) {
            penalties.connectedAccountFor(reservation).ifPresent(account -> {
                connect.detachCard(reservation.getStripePaymentMethodId(), account);
                penalties.markCardForgotten(reservation.getId());
            });
        }
    }

    private void charge(Reservation reservation) {
        String account = penalties.connectedAccountFor(reservation).orElse(null);
        NoShowCharge charge = penalties.chargeFor(reservation, account).orElse(null);
        if (charge == null) {
            // Nothing to debit: no card, no amount, or no account able to receive it.
            penalties.settleFailed(reservation.getId(), "Aucun moyen de paiement exploitable");
            return;
        }
        try {
            penalties.settleCharged(reservation.getId(), connect.chargeNoShowPenalty(charge));
        } catch (PaymentGatewayException e) {
            penalties.settleFailed(reservation.getId(), e.getMessage());
        }
    }
}
