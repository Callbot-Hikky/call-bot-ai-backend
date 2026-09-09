package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.PayoutResponse;
import com.callbot.ai.exception.InvalidRequestException;
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
 * The transactional half of paying restaurateurs their collected booking fees.
 *
 * <p>The money is already theirs — a destination charge credited their connected
 * account the moment the diner paid. What is held back is only the bank transfer, and
 * only until a day after the service, so that a cancellation inside the promised window
 * can still be refunded in full from a balance that has not left.
 *
 * <p>{@link #claim} and {@link #settle} are two short transactions with the Stripe call
 * in between, orchestrated by {@link PayoutJob}. They live apart from that orchestration
 * on purpose: a method calling its own {@code @Transactional} sibling goes straight
 * through the proxy and gets no new transaction at all.
 *
 * <p>Holding one transaction across the Stripe call would keep every row locked for the
 * length of the whole sweep, and a commit failing after the money had left would leave
 * it unrecorded — and sent a second time the following night.
 */
@Service
@RequiredArgsConstructor
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final ReservationRepository reservationRepository;
    private final OrganizationRepository organizationRepository;
    private final PayoutRepository payoutRepository;
    private final CallerOrganizationResolver callerOrganization;

    @Transactional(readOnly = true)
    public List<UUID> organizationsWithMoneyDue(OffsetDateTime now) {
        return reservationRepository.findOrganizationsWithDuePayouts(now);
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

    /**
     * Step 1. Locks what is due, groups it by currency, and stamps each reservation with
     * the payout that now owns it. Committing here is what makes the claim exclusive.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Payout> claim(UUID organizationId, OffsetDateTime now) {
        List<Reservation> due = reservationRepository.lockDuePayoutsFor(organizationId, now);
        if (due.isEmpty()) {
            // Another instance took them between the two queries.
            return List.of();
        }

        Map<String, List<Reservation>> byCurrency = new LinkedHashMap<>();
        for (Reservation reservation : due) {
            byCurrency.computeIfAbsent(reservation.getCurrency(), currency -> new ArrayList<>())
                    .add(reservation);
        }

        List<Payout> claimed = new ArrayList<>();
        OffsetDateTime claimedAt = OffsetDateTime.now();
        for (Map.Entry<String, List<Reservation>> entry : byCurrency.entrySet()) {
            List<Reservation> reservations = entry.getValue();
            int amountCents = reservations.stream().mapToInt(PayoutService::restaurateurShareOf).sum();
            if (amountCents <= 0) {
                continue;
            }
            Payout payout = payoutRepository.save(Payout.builder()
                    .organizationId(organizationId)
                    .amountCents(amountCents)
                    .currency(entry.getKey())
                    .reservationCount(reservations.size())
                    .status(PayoutStatus.PENDING)
                    .build());
            for (Reservation reservation : reservations) {
                reservation.setPaidOutAt(claimedAt);
                reservation.setPayoutId(payout.getId());
            }
            reservationRepository.saveAll(reservations);
            claimed.add(payout);
        }
        return claimed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(UUID payoutId, String stripePayoutId, String failureMessage) {
        Payout payout = payoutRepository.findById(payoutId).orElse(null);
        if (payout == null) {
            log.error("Payout {} vanished while Stripe was being called", payoutId);
            return;
        }
        if (failureMessage == null) {
            payout.setStripePayoutId(stripePayoutId);
            payout.setStatus(PayoutStatus.PAID);
            payoutRepository.save(payout);
            return;
        }

        payout.setStatus(PayoutStatus.FAILED);
        payout.setFailureMessage(failureMessage);
        payoutRepository.save(payout);
        release(payoutId);
    }

    /** Hands the reservations back to the next sweep after a refused transfer. */
    private void release(UUID payoutId) {
        List<Reservation> reservations = reservationRepository.findByPayoutId(payoutId);
        for (Reservation reservation : reservations) {
            reservation.setPaidOutAt(null);
            reservation.setPayoutId(null);
        }
        reservationRepository.saveAll(reservations);
    }

    /** The organization, only if Stripe will actually accept a transfer to it. */
    @Transactional(readOnly = true)
    public Optional<Organization> payableOrganization(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .filter(organization -> organization.getStripeAccountId() != null
                        && organization.isStripePayoutsEnabled());
    }

    /** The restaurateur receives the fee less Alloquence's commission, as Stripe already split it. */
    private static int restaurateurShareOf(Reservation reservation) {
        int gross = reservation.getGuaranteeAmountCents() == null ? 0 : reservation.getGuaranteeAmountCents();
        int fee = reservation.getApplicationFeeCents() == null ? 0 : reservation.getApplicationFeeCents();
        return Math.max(0, gross - fee);
    }
}
