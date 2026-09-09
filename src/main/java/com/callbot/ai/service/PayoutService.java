package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.PayoutResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Payout;
import com.callbot.ai.model.PayoutStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.PayoutRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.security.CallerOrganizationResolver;

import lombok.RequiredArgsConstructor;

/**
 * Sends collected booking fees on to the restaurateurs.
 *
 * <p>The money is already theirs — a destination charge credited their connected
 * account the moment the diner paid. What is held back is only the bank transfer, and
 * only until a day after the service, so that a cancellation inside the promised window
 * can still be refunded in full from a balance that has not left.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final ReservationRepository reservationRepository;
    private final OrganizationRepository organizationRepository;
    private final PayoutRepository payoutRepository;
    private final StripeConnectGateway connect;
    private final CallerOrganizationResolver callerOrganization;

    /** @return how many organizations were paid */
    public int payOutEverythingDue() {
        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> organizationIds = reservationRepository.findOrganizationsWithDuePayouts(now);
        int paid = 0;
        for (UUID organizationId : organizationIds) {
            if (payOut(organizationId, now)) {
                paid++;
            }
        }
        return paid;
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> list(String callerEmail) {
        UUID organizationId = callerOrganization.resolve(callerEmail)
                .orElseThrow(() -> new InvalidRequestException(
                        "Seul un utilisateur signé peut consulter ses reversements"));
        return payoutRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(PayoutResponse::from)
                .toList();
    }

    private boolean payOut(UUID organizationId, OffsetDateTime now) {
        Organization organization = organizationRepository.findById(organizationId).orElse(null);
        if (organization == null || organization.getStripeAccountId() == null
                || !organization.isStripePayoutsEnabled()) {
            log.warn("Organization {} has money due but no account able to receive it", organizationId);
            return false;
        }

        List<Reservation> due = reservationRepository.lockDuePayoutsFor(organizationId, now);
        if (due.isEmpty()) {
            // Another instance took them between the two queries.
            return false;
        }

        int amountCents = due.stream().mapToInt(PayoutService::netOf).sum();
        if (amountCents <= 0) {
            return false;
        }
        String currency = due.get(0).getCurrency();

        Payout payout = payoutRepository.save(Payout.builder()
                .organizationId(organizationId)
                .amountCents(amountCents)
                .currency(currency)
                .reservationCount(due.size())
                .status(PayoutStatus.PENDING)
                .build());

        try {
            payout.setStripePayoutId(connect.payOut(organization.getStripeAccountId(), amountCents, currency));
            payout.setStatus(PayoutStatus.PAID);
        } catch (PaymentGatewayException e) {
            // Usually a balance Stripe has not made available yet. The reservations stay
            // unstamped, so the next run picks them up again.
            log.warn("Payout of {} {} to organization {} failed: {}",
                    amountCents, currency, organizationId, e.getMessage());
            payout.setStatus(PayoutStatus.FAILED);
            payout.setFailureMessage(e.getMessage());
            payoutRepository.save(payout);
            return false;
        }

        payoutRepository.save(payout);
        OffsetDateTime paidOutAt = OffsetDateTime.now();
        for (Reservation reservation : due) {
            reservation.setPaidOutAt(paidOutAt);
            reservation.setPayoutId(payout.getId());
        }
        reservationRepository.saveAll(due);
        return true;
    }

    /** The restaurateur receives the fee less Alloquence's commission, as Stripe already split it. */
    private static int netOf(Reservation reservation) {
        int gross = reservation.getGuaranteeAmountCents() == null ? 0 : reservation.getGuaranteeAmountCents();
        int fee = reservation.getApplicationFeeCents() == null ? 0 : reservation.getApplicationFeeCents();
        return Math.max(0, gross - fee);
    }
}
