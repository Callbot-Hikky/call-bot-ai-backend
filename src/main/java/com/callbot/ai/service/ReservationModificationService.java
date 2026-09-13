package com.callbot.ai.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.GuestModificationRequest;
import com.callbot.ai.dto.GuestModificationResponse;
import com.callbot.ai.dto.PublicModificationResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.notification.ReservationModifiedByGuestEvent;
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

    private final ReservationRepository reservations;
    private final RestaurantRepository restaurants;
    private final ReservationService reservationService;
    private final PartySizeChangePolicy partySizeChangePolicy;
    private final PartySizeTopUpService topUps;
    private final PartySizeRefund partySizeRefund;
    private final TableAvailability availability;
    private final ApplicationEventPublisher events;

    /**
     * What the diner is shown behind their link.
     *
     * <p>Three answers, not two. A link whose window has closed still describes the
     * booking and says so with {@code open} — someone who followed a link deserves a page
     * telling them why nothing can be changed, and their table is still theirs to see.
     *
     * <p>A booking that is cancelled or already served is different: the link has outlived
     * what it pointed at, and goes on being a URL in an old message. It still resolves,
     * but it hands back nothing except who to call.
     */
    @Transactional(readOnly = true)
    public PublicModificationResponse describe(String modificationToken) {
        Reservation reservation = byModificationToken(modificationToken);
        Restaurant restaurant = restaurantOf(reservation);
        if (hasOutlivedItsBooking(reservation)) {
            return PublicModificationResponse.spent(reservation, restaurant);
        }
        return PublicModificationResponse.of(reservation, restaurant,
                isOpen(reservation), closesAt(reservation));
    }

    /** Cancelled, or the service has been and gone. */
    private boolean hasOutlivedItsBooking(Reservation reservation) {
        return ReservationStatus.CANCELLED.equals(reservation.getStatus())
                || reservation.getStartsAt().isBefore(OffsetDateTime.now());
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
            refunded = partySizeRefund.handBackCoversGivenUp(
                    reservation, previousPartySize, effectivePartySize);
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

        if (verdict == PartySizeChange.COLLECT_TOP_UP) {
            return GuestModificationResponse.owing(saved.getStartsAt(), saved.getPartySize(),
                    topUps.open(saved, wantedPartySize));
        }
        return GuestModificationResponse.applied(
                saved.getStartsAt(), saved.getPartySize(), refunded);
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
     * When this reservation stops being the diner's to change.
     *
     * <p>The window frozen on the reservation is the one that counts, not the restaurant's
     * current setting: it is what they were promised. Zero means up to the service itself,
     * the reading the refund window already gives it.
     */
    private OffsetDateTime closesAt(Reservation reservation) {
        return closesFor(reservation, reservation.getStartsAt());
    }

    /** When a service starting at {@code startsAt} stops being open to change. */
    private OffsetDateTime closesFor(Reservation reservation, OffsetDateTime startsAt) {
        int windowHours = reservation.getModificationWindowHours() == null
                ? 0
                : reservation.getModificationWindowHours();
        return startsAt.minusHours(windowHours);
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
        if (!OffsetDateTime.now().isBefore(closesFor(reservation, startsAt))) {
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
