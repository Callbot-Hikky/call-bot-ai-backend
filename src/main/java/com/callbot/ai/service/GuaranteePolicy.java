package com.callbot.ai.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;

/**
 * Applies a restaurant's guarantee mode to a reservation being taken.
 *
 * <p>Reservations are born on the phone, so the diner cannot pay during the call.
 * The table is therefore pre-held for a short window while they follow a link; if
 * the window closes without them acting, the table goes back on sale. The mode and
 * the amount are copied onto the reservation, never read back from the restaurant:
 * a restaurateur changing their settings must not alter bookings already accepted.
 */
@Component
public class GuaranteePolicy {

    /** How long the table is held while the diner secures their reservation. */
    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(30);

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * @param exemptedBy the staff member waiving the guarantee, or {@code null} when
     *                   the guarantee is required as usual
     */
    public void applyOnCreation(Reservation reservation, Restaurant restaurant, UUID exemptedBy) {
        GuaranteeMode mode = GuaranteeMode.fromCode(restaurant.getGuaranteeMode());
        reservation.setGuaranteeMode(mode.code());
        // The diner has no account: cancelling later is only possible through this key.
        reservation.setCancellationToken(newToken());

        if (!mode.requiresGuarantee()) {
            reservation.setGuaranteeStatus(GuaranteeStatus.NOT_REQUIRED);
            return;
        }

        int perGuest = perGuestFor(mode, restaurant);
        // Both are frozen: the unit price, because a top-up for extra guests is priced
        // off it long after the restaurateur may have changed their settings, and the
        // total, because it is what the diner was told to pay.
        reservation.setGuaranteeCentsPerGuest(perGuest);
        reservation.setGuaranteeAmountCents(perGuest * requirePartySize(reservation));
        // Frozen alongside the amount: the window is half of what was promised to the
        // diner, and a restaurateur shortening it must not shorten it for them.
        reservation.setGuaranteeRefundWindowHours(restaurant.getRefundWindowHours());

        if (exemptedBy != null) {
            reservation.setGuaranteeStatus(GuaranteeStatus.EXEMPTED);
            reservation.setGuaranteeExemptedBy(exemptedBy);
            return;
        }

        reservation.setStatus(ReservationStatus.AWAITING_PAYMENT);
        reservation.setGuaranteeStatus(GuaranteeStatus.AWAITING);
        reservation.setPaymentToken(newToken());
        reservation.setGuaranteeExpiresAt(OffsetDateTime.now().plus(PAYMENT_WINDOW));
    }

    /** The damage a no-show causes grows with the party, so amounts are per guest. */
    private int perGuestFor(GuaranteeMode mode, Restaurant restaurant) {
        Integer perGuest = mode == GuaranteeMode.BOOKING_FEE
                ? restaurant.getBookingFeeCentsPerGuest()
                : restaurant.getNoShowPenaltyCentsPerGuest();
        if (perGuest == null) {
            // The schema forbids this; a restaurant in a paying mode always has an amount.
            throw new IllegalStateException(
                    "Restaurant " + restaurant.getId() + " is in mode " + mode.code() + " without an amount");
        }
        return perGuest;
    }

    private int requirePartySize(Reservation reservation) {
        Integer partySize = reservation.getPartySize();
        if (partySize == null || partySize <= 0) {
            // Both entry points validate this, so reaching here means a caller bypassed
            // validation. Charging for one guest would silently undercharge the table.
            throw new IllegalStateException("Cannot price a guarantee without a party size");
        }
        return partySize;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
