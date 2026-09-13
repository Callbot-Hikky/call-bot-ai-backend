package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.PayoutResponse;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.Payout;
import com.callbot.ai.model.PayoutStatus;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.PayoutRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.security.OrganizationScope;

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

    private final ReservationChargeRepository charges;
    private final RestaurantRepository restaurantRepository;
    private final PayoutRepository payoutRepository;
    private final OrganizationScope scope;

    @Transactional(readOnly = true)
    public List<UUID> restaurantsWithMoneyDue(OffsetDateTime now) {
        return charges.findRestaurantsWithDuePayouts(ChargeStatus.PAID, now);
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> list(UUID restaurantId, String callerEmail) {
        scope.requireOwnedRestaurant(restaurantId, callerEmail);
        return payoutRepository.findByRestaurantIdOrderByCreatedAtDesc(restaurantId).stream()
                .map(PayoutResponse::from)
                .toList();
    }

    /**
     * Step 1. Locks what is due, groups it by currency, and stamps each charge with the
     * payout that now owns it. Committing here is what makes the claim exclusive.
     *
     * <p>What is grouped is charges, not reservations: a party that grew paid twice, and
     * both movements are owed to the restaurateur. The reservation count reported on the
     * payout therefore counts reservations once, however many charges each contributed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Payout> claim(UUID restaurantId, OffsetDateTime now) {
        List<ReservationCharge> due =
                charges.lockDuePayoutsFor(restaurantId, ChargeStatus.PAID, now);
        if (due.isEmpty()) {
            // Another instance took them between the two queries.
            return List.of();
        }

        Map<String, List<ReservationCharge>> byCurrency = new LinkedHashMap<>();
        for (ReservationCharge charge : due) {
            byCurrency.computeIfAbsent(charge.getCurrency(), currency -> new ArrayList<>())
                    .add(charge);
        }

        List<Payout> claimed = new ArrayList<>();
        OffsetDateTime claimedAt = OffsetDateTime.now();
        for (Map.Entry<String, List<ReservationCharge>> entry : byCurrency.entrySet()) {
            List<ReservationCharge> settled = entry.getValue();
            int amountCents = settled.stream()
                    .mapToInt(ReservationCharge::restaurateurShareCents).sum();
            if (amountCents <= 0) {
                continue;
            }
            Set<UUID> reservations = new LinkedHashSet<>();
            for (ReservationCharge charge : settled) {
                reservations.add(charge.getReservationId());
            }
            Payout payout = payoutRepository.save(Payout.builder()
                    .restaurantId(restaurantId)
                    .amountCents(amountCents)
                    .currency(entry.getKey())
                    .reservationCount(reservations.size())
                    .status(PayoutStatus.PENDING)
                    .build());
            for (ReservationCharge charge : settled) {
                charge.setPaidOutAt(claimedAt);
                charge.setPayoutId(payout.getId());
            }
            charges.saveAll(settled);
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

    /** Hands the charges back to the next sweep after a refused transfer. */
    private void release(UUID payoutId) {
        List<ReservationCharge> claimed = charges.findByPayoutId(payoutId);
        for (ReservationCharge charge : claimed) {
            charge.setPaidOutAt(null);
            charge.setPayoutId(null);
        }
        charges.saveAll(claimed);
    }

    /** The restaurant, only if Stripe will actually accept a transfer to its account. */
    @Transactional(readOnly = true)
    public Optional<Restaurant> payableRestaurant(UUID restaurantId) {
        return restaurantRepository.findById(restaurantId)
                .filter(restaurant -> restaurant.getStripeAccountId() != null
                        && restaurant.isStripePayoutsEnabled());
    }
}
