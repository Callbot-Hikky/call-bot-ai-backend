package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.repository.ReservationChargeRepository;

import lombok.RequiredArgsConstructor;

/**
 * Hands back what was paid for covers a party has given up.
 *
 * <p>The exact mirror of {@link PartySizeTopUpService}: one prices covers being added,
 * this one refunds covers being dropped, and both read the amount frozen when the
 * reservation was taken rather than the restaurant's current setting.
 *
 * <p>It belongs to neither caller. A diner shrinking their own party and a staff member
 * doing it for them over the telephone are the same event, and answering them differently
 * would mean the refund depended on who happened to press the button.
 */
@Component
@RequiredArgsConstructor
public class PartySizeRefund {

    private static final Logger log = LoggerFactory.getLogger(PartySizeRefund.class);

    private final ReservationChargeRepository charges;
    private final StripeConnectGateway connect;

    /**
     * Gives back {@code (from - to)} covers' worth of what was collected.
     *
     * <p>Only a booking fee comes back, and only once it has actually arrived. A no-show
     * guarantee holds a card it has never charged — the price per guest on such a
     * reservation is the <em>penalty</em>, and reading it as money owed back would promise
     * a refund of something that never came in. A fee still awaiting payment is repriced
     * instead, so the diner is not asked to settle a link covering covers they have just
     * given up.
     *
     * <p>Drawn from the most recent payment first: the covers being removed are the ones
     * most recently added, so a party that grew through a top-up and then shrank hands
     * that top-up back before it touches the original fee. A no-show penalty is never
     * drawn on — it answers an absence, not a cover.
     *
     * @return what actually went back, in cents; zero when no money ever stood behind
     *         those covers
     */
    public int handBackCoversGivenUp(Reservation reservation, int from, int to) {
        Integer perGuest = reservation.getGuaranteeCentsPerGuest();
        if (to >= from || perGuest == null || perGuest <= 0) {
            return 0;
        }
        if (!GuaranteeMode.BOOKING_FEE.code().equals(reservation.getGuaranteeMode())) {
            return 0;
        }
        if (GuaranteeStatus.AWAITING.equals(reservation.getGuaranteeStatus())) {
            reservation.setGuaranteeAmountCents(perGuest * to);
            return 0;
        }
        if (!GuaranteeStatus.SECURED.equals(reservation.getGuaranteeStatus())) {
            // Waived, already handed back, or never asked for: the price per cover
            // survives on the row, but no money ever stood behind it. The rule follows
            // the money, exactly as it does when a party grows.
            return 0;
        }

        int owed = perGuest * (from - to);
        OffsetDateTime now = OffsetDateTime.now();
        int handedBack = 0;

        for (ReservationCharge charge : refundable(reservation)) {
            if (handedBack >= owed) {
                break;
            }
            int available = charge.refundableCents();
            if (available <= 0) {
                continue;
            }
            int take = Math.min(available, owed - handedBack);

            // The register is written before Stripe is called because the idempotency key
            // is the running total, which only reads correctly once the write has landed.
            // Note this is not a safety ordering: a Stripe failure rolls the register back
            // under either order, and a failure after a successful refund leaves money
            // moved whichever way round these two sit.
            charge.refundPartially(now, take);
            charges.save(charge);
            connect.refundPartially(charge.getStripePaymentIntentId(), take,
                    "covers-" + charge.getId() + "-" + charge.getRefundedAmountCents());
            handedBack += take;
        }

        if (handedBack < owed) {
            // The reservation says a fee was collected, yet the register holds less than
            // the covers given up are worth — money already sent to the restaurateur's
            // bank, most likely, which the payout delay is supposed to forbid before the
            // service. Said out loud rather than swallowed: the difference is owed to a
            // diner and somebody has to send it by hand.
            log.error("Reservation {} owes {} cents back for covers given up, but only {} could be "
                    + "found in the register to return",
                    reservation.getId(), owed, handedBack);
        }
        return handedBack;
    }

    /** Settled money on this reservation that has not yet left for the restaurateur's bank. */
    private List<ReservationCharge> refundable(Reservation reservation) {
        return charges.findByReservationIdAndStatus(reservation.getId(), ChargeStatus.PAID).stream()
                .filter(charge -> !ChargeKind.NO_SHOW_PENALTY.equals(charge.getKind()))
                .filter(charge -> charge.getPaidOutAt() == null)
                .sorted(Comparator.comparing(ReservationCharge::getPaidAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
    }
}
