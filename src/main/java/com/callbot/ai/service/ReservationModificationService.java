package com.callbot.ai.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.GuestModificationRequest;
import com.callbot.ai.dto.GuestModificationResponse;
import com.callbot.ai.dto.PendingTopUpResponse;
import com.callbot.ai.dto.PublicModificationResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.notification.ReservationModifiedByGuestEvent;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * The diner's own hand on their booking: how many of them, and when.
 *
 * <p>Reached with a token and nothing else, like every other diner-facing service — they
 * booked by telephone and have no account. The token differs from the payment link in one
 * way that matters: it is not consumed. A party that goes from four to six and then to
 * five walks the same link twice, and burning it on the first pass would mean sending a
 * fresh one after every change.
 *
 * <p>Nothing here decides what a change of party size costs. That verdict belongs to
 * {@link PartySizeChangePolicy} and is the same one the dashboard gets: a diner and a
 * staff member raising a party to eight must not be answered differently. What this
 * service adds on top is the diner's side of it — whether the link is still open at all,
 * where the party sits once the hour moves, and what comes back when it shrinks.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ReservationModificationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationModificationService.class);

    private final ReservationRepository reservations;
    private final RestaurantRepository restaurants;
    private final ReservationChargeRepository charges;
    private final ReservationService reservationService;
    private final PartySizeChangePolicy partySizeChangePolicy;
    private final PartySizeTopUpService topUps;
    private final TableAvailability availability;
    private final StripeConnectGateway connect;
    private final ApplicationEventPublisher events;

    /**
     * What the diner is shown behind their link.
     *
     * <p>A closed link still describes the reservation, and says so with {@code open}
     * rather than failing: someone who followed a link deserves a page telling them why
     * nothing can be changed, not one implying the booking never existed.
     */
    @Transactional(readOnly = true)
    public PublicModificationResponse describe(String modificationToken) {
        Reservation reservation = byModificationToken(modificationToken);
        return PublicModificationResponse.of(reservation, restaurantOf(reservation),
                isOpen(reservation), closesAt(reservation));
    }

    /**
     * The slots this reservation could move to, for the party the diner is considering.
     *
     * <p>{@code partySize} is a preview, not a change: the picker must redraw as the
     * number of covers moves, because a table for eight is not a table for two. The
     * search itself is the dashboard's, unchanged — two implementations would eventually
     * disagree and someone would pick a slot that was never free.
     */
    @Transactional(readOnly = true)
    public RescheduleSlotsResponse slots(String modificationToken, Integer partySize,
            LocalDate fromDate) {
        Reservation reservation = byModificationToken(modificationToken);
        requireOpen(reservation);
        return reservationService.slotsFor(reservation, fromDate, partySize);
    }

    /**
     * Applies what the diner asked for, as far as the rule and the room allow.
     *
     * <p>Order matters here. The window is checked first, against both the hour they hold
     * and the hour they want — otherwise a diner could dodge a three-hour cutoff by
     * dragging the service into it. Then the party-size verdict, before anything is
     * written, because a rise that owes money leaves the party exactly where it was. Then
     * the room, for the party that will actually be sitting there. Only then does money
     * move.
     *
     * <p>The availability answer is a finding, not a reservation of anything, and it was
     * true a moment ago rather than at the instant the diner read the page: two diners
     * racing for the last table means the second one is turned away here, with a reason,
     * rather than seated on a table that is gone.
     */
    public GuestModificationResponse apply(String modificationToken, GuestModificationRequest request) {
        Reservation reservation = byModificationToken(modificationToken);
        requireOpen(reservation);

        int previousPartySize = reservation.getPartySize();
        OffsetDateTime previousStartsAt = reservation.getStartsAt();
        Duration sitting = Duration.between(previousStartsAt, reservation.getEndsAt());

        int wantedPartySize = request.partySize() != null ? request.partySize() : previousPartySize;
        OffsetDateTime wantedStartsAt = request.startsAt() != null ? request.startsAt() : previousStartsAt;
        OffsetDateTime wantedEndsAt = wantedStartsAt.plus(sitting);
        boolean slotMoves = !wantedStartsAt.isEqual(previousStartsAt);
        if (slotMoves) {
            requireServiceFarEnoughOff(reservation, wantedStartsAt);
        }

        // The rule first, and on the slot the reservation would occupy: a rise is weighed
        // against the room at the hour it is actually asking for.
        PartySizeChange verdict = partySizeChangePolicy.decide(
                reservation, wantedPartySize, wantedStartsAt, wantedEndsAt);
        // A rise that owes money changes nothing yet. Everything downstream — the table,
        // the refund, what the restaurant is told — is about the party that will really
        // be sitting there, which until the difference is settled is the old one.
        int effectivePartySize = verdict == PartySizeChange.COLLECT_TOP_UP
                ? previousPartySize
                : wantedPartySize;

        if (slotMoves) {
            reseat(reservation, effectivePartySize, wantedStartsAt, wantedEndsAt);
            reservation.setStartsAt(wantedStartsAt);
            reservation.setEndsAt(wantedEndsAt);
        }

        int refunded = 0;
        if (effectivePartySize < previousPartySize) {
            refunded = handBackTheCoversGivenUp(reservation, previousPartySize, effectivePartySize);
        }
        reservation.setPartySize(effectivePartySize);
        Reservation saved = reservations.save(reservation);

        if (verdict == PartySizeChange.APPLY_AND_LAPSE_TOP_UP) {
            topUps.lapsePendingFor(saved.getId(), "the diner revised their party down");
        }

        boolean somethingMoved = slotMoves || effectivePartySize != previousPartySize;
        if (somethingMoved) {
            events.publishEvent(new ReservationModifiedByGuestEvent(
                    saved.getId(), previousPartySize, previousStartsAt, refunded));
        }

        PendingTopUpResponse pendingTopUp = verdict == PartySizeChange.COLLECT_TOP_UP
                ? PendingTopUpResponse.of(topUps.open(saved, wantedPartySize))
                : null;
        return new GuestModificationResponse(
                saved.getStartsAt(), saved.getPartySize(), refunded, pendingTopUp);
    }

    /**
     * Puts the party somewhere at the new hour, keeping the table they have when it will
     * still hold them.
     *
     * <p>Only on a move. A party that merely shrank stays exactly where the staff put it:
     * the room was laid out around where people are sitting, and a move nobody asked for
     * is a move someone has to notice and undo.
     */
    private void reseat(Reservation reservation, int partySize, OffsetDateTime startsAt,
            OffsetDateTime endsAt) {
        RestaurantTable seating = availability.firstSeating(reservation.getRestaurantId(),
                partySize, startsAt, endsAt, reservation.getId(), reservation.getTableId());
        if (seating == null) {
            throw new InvalidRequestException(
                    "Aucune table n'est libre sur ce créneau pour " + partySize + " personnes");
        }
        reservation.setTableId(seating.getId());
    }

    /**
     * Gives back what was paid for the covers the diner has just given up.
     *
     * <p>Priced off the amount frozen when the reservation was taken, never the
     * restaurant's current setting — the same rule the top-up is priced by, for the same
     * reason.
     *
     * <p>Drawn from the most recent payment first. The covers being removed are the ones
     * most recently added, so a party that grew through a top-up and then fell hands that
     * top-up back before it touches the original fee. A no-show penalty is never drawn on:
     * it answers an absence, not a cover, and shrinking a party is not a way to reclaim it.
     *
     * <p>Nothing is handed back for money that has not arrived. A fee still awaiting
     * payment is repriced instead: the diner would otherwise be asked to settle a link
     * covering covers they no longer want.
     *
     * @return what actually went back, in cents
     */
    private int handBackTheCoversGivenUp(Reservation reservation, int from, int to) {
        Integer perGuest = reservation.getGuaranteeCentsPerGuest();
        if (perGuest == null || perGuest <= 0) {
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
            int left = charge.getAmountCents()
                    - (charge.getRefundedAmountCents() == null ? 0 : charge.getRefundedAmountCents());
            if (left <= 0) {
                continue;
            }
            int take = Math.min(left, owed - handedBack);

            // The register is written before Stripe is called. Both are in one
            // transaction, so either order rolls back on failure — but this order makes
            // the failure that rolls back the one where no money moved.
            charge.refundPartially(now, take);
            charges.save(charge);
            // Keyed on the charge and its running total, so a retry after a lost answer
            // cannot hand the same covers back twice.
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

    /**
     * When this reservation stops being the diner's to change.
     *
     * <p>The window frozen on the reservation is the one that counts, not the restaurant's
     * current setting: it is what they were promised. Zero means up to the service itself,
     * the reading the refund window already gives it.
     */
    private OffsetDateTime closesAt(Reservation reservation) {
        int windowHours = reservation.getModificationWindowHours() == null
                ? 0
                : reservation.getModificationWindowHours();
        return reservation.getStartsAt().minusHours(windowHours);
    }

    private boolean isOpen(Reservation reservation) {
        return !ReservationStatus.CANCELLED.equals(reservation.getStatus())
                && OffsetDateTime.now().isBefore(closesAt(reservation));
    }

    private void requireOpen(Reservation reservation) {
        if (ReservationStatus.CANCELLED.equals(reservation.getStatus())) {
            throw new InvalidRequestException("Cette réservation est annulée");
        }
        if (!OffsetDateTime.now().isBefore(closesAt(reservation))) {
            throw new InvalidRequestException(
                    "Les modifications sont closes : appelez le restaurant");
        }
    }

    /**
     * The hour being moved to must itself be far enough off.
     *
     * <p>Without this the cutoff is bypassed by moving the service into it: a diner three
     * days out could pull their table to an hour from now, which is exactly what a
     * restaurateur setting a window is refusing.
     */
    private void requireServiceFarEnoughOff(Reservation reservation, OffsetDateTime startsAt) {
        int windowHours = reservation.getModificationWindowHours() == null
                ? 0
                : reservation.getModificationWindowHours();
        if (!OffsetDateTime.now().isBefore(startsAt.minusHours(windowHours))) {
            throw new InvalidRequestException(
                    "Ce créneau est trop proche pour être réservé en ligne : appelez le restaurant");
        }
    }

    /** The token itself never travels back in the error: it is a secret, and errors are logged. */
    private Reservation byModificationToken(String modificationToken) {
        return reservations.findByModificationToken(modificationToken)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", "this modification link"));
    }

    private Restaurant restaurantOf(Reservation reservation) {
        return restaurants.findById(reservation.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", reservation.getRestaurantId()));
    }
}
