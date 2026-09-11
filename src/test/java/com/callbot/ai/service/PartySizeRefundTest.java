package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.repository.ReservationChargeRepository;

/**
 * What comes back when a party gives covers up — the mirror of
 * {@link PartySizeTopUpServiceTest}, which tests what is owed when it grows.
 */
@ExtendWith(MockitoExtension.class)
class PartySizeRefundTest {

    @Mock
    private ReservationChargeRepository charges;
    @Mock
    private StripeConnectGateway connect;
    @InjectMocks
    private PartySizeRefund refund;

    private final UUID reservationId = UUID.randomUUID();

    /** A paid table of six, 15,00 € a cover. */
    private Reservation reservation() {
        return Reservation.builder()
                .id(reservationId)
                .partySize(6)
                .startsAt(OffsetDateTime.now().plusDays(30))
                .endsAt(OffsetDateTime.now().plusDays(30).plusHours(2))
                .guaranteeMode(GuaranteeMode.BOOKING_FEE.code())
                .guaranteeStatus(GuaranteeStatus.SECURED)
                .guaranteeCentsPerGuest(1500)
                .guaranteeAmountCents(9000)
                .currency("eur")
                .build();
    }

    private ReservationCharge bookingFee() {
        return ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.BOOKING_FEE)
                .status(ChargeStatus.PAID)
                .amountCents(9000)
                .applicationFeeCents(900)
                .currency("eur")
                .paidAt(OffsetDateTime.now().minusDays(1))
                .stripePaymentIntentId("pi_booking")
                .payoutEligibleAt(OffsetDateTime.now().plusDays(31))
                .build();
    }

    private ReservationCharge topUp(int amountCents) {
        return ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.PARTY_SIZE_TOP_UP)
                .status(ChargeStatus.PAID)
                .amountCents(amountCents)
                .applicationFeeCents(amountCents / 10)
                .currency("eur")
                .paidAt(OffsetDateTime.now())
                .stripePaymentIntentId("pi_top_up")
                .payoutEligibleAt(OffsetDateTime.now().plusDays(31))
                .build();
    }

    private void paid(ReservationCharge... rows) {
        lenient().when(charges.findByReservationIdAndStatus(reservationId, ChargeStatus.PAID))
                .thenReturn(List.of(rows));
    }

    @Test
    void handsBackThePricePerCoverFrozenAtBooking() {
        Reservation reservation = reservation();
        ReservationCharge fee = bookingFee();
        paid(fee);

        int handedBack = refund.handBackCoversGivenUp(reservation, 6, 4);

        // Two covers at 15,00 €.
        assertThat(handedBack).isEqualTo(3000);
        assertThat(fee.getRefundedAmountCents()).isEqualTo(3000);
        verify(connect).refundPartially(eq("pi_booking"), eq(3000), anyString());
    }

    @Test
    void drawsFromWhatWasPaidLast() {
        // The covers being removed are the ones most recently added, so the top-up that
        // bought them gives way before the original fee.
        Reservation reservation = reservation();
        paid(bookingFee(), topUp(3000));

        refund.handBackCoversGivenUp(reservation, 6, 4);

        verify(connect).refundPartially(eq("pi_top_up"), eq(3000), anyString());
        verify(connect, never()).refundPartially(eq("pi_booking"), anyInt(), anyString());
    }

    @Test
    void spillsOntoTheNextChargeWhenOneIsNotEnough() {
        Reservation reservation = reservation();
        paid(bookingFee(), topUp(1500));

        // Six to three: 45,00 € owed, of which the top-up holds only 15,00 €.
        assertThat(refund.handBackCoversGivenUp(reservation, 6, 3)).isEqualTo(4500);
        verify(connect).refundPartially(eq("pi_top_up"), eq(1500), anyString());
        verify(connect).refundPartially(eq("pi_booking"), eq(3000), anyString());
    }

    @Test
    void neverDrawsOnANoShowPenalty() {
        // A penalty answers an absence, not a cover, even when it is the most recent
        // money on the reservation.
        Reservation reservation = reservation();
        ReservationCharge penalty = ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.NO_SHOW_PENALTY)
                .status(ChargeStatus.PAID)
                .amountCents(9000)
                .currency("eur")
                .paidAt(OffsetDateTime.now())
                .stripePaymentIntentId("pi_penalty")
                .build();
        paid(bookingFee(), penalty);

        assertThat(refund.handBackCoversGivenUp(reservation, 6, 4)).isEqualTo(3000);
        verify(connect).refundPartially(eq("pi_booking"), eq(3000), anyString());
        verify(connect, never()).refundPartially(eq("pi_penalty"), anyInt(), anyString());
    }

    @Test
    void neverDrawsOnMoneyAlreadySentToTheRestaurateur() {
        Reservation reservation = reservation();
        ReservationCharge paidOut = bookingFee();
        paidOut.setPaidOutAt(OffsetDateTime.now().minusHours(1));
        paid(paidOut);

        assertThat(refund.handBackCoversGivenUp(reservation, 6, 4)).isZero();
        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }

    @Test
    void underANoShowGuarantee_handsBackNothing() {
        // A card was registered and never charged. The price per guest on such a row is
        // the PENALTY: reading it as money owed back would promise a refund of something
        // that never came in.
        Reservation reservation = reservation();
        reservation.setGuaranteeMode(GuaranteeMode.NO_SHOW.code());
        paid();

        assertThat(refund.handBackCoversGivenUp(reservation, 6, 4)).isZero();
        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }

    @Test
    void onAFreeReservation_handsBackNothing() {
        Reservation reservation = reservation();
        reservation.setGuaranteeMode(GuaranteeMode.NONE.code());
        reservation.setGuaranteeStatus(GuaranteeStatus.NOT_REQUIRED);
        reservation.setGuaranteeCentsPerGuest(null);
        paid();

        assertThat(refund.handBackCoversGivenUp(reservation, 6, 4)).isZero();
        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }

    @Test
    void onAWaivedFee_handsBackNothing() {
        // Staff waived it: the price per cover survives on the row, but no money ever
        // stood behind it. The rule follows the money, as it does when a party grows.
        Reservation reservation = reservation();
        reservation.setGuaranteeStatus(GuaranteeStatus.EXEMPTED);
        paid();

        assertThat(refund.handBackCoversGivenUp(reservation, 6, 4)).isZero();
        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }

    @Test
    void onAFeeNotYetPaid_repricesInsteadOfRefunding() {
        // The link was sent for six and has not been followed. Leaving the amount alone
        // would ask them to settle covers they have just given up.
        Reservation reservation = reservation();
        reservation.setGuaranteeStatus(GuaranteeStatus.AWAITING);
        paid();

        assertThat(refund.handBackCoversGivenUp(reservation, 6, 4)).isZero();
        assertThat(reservation.getGuaranteeAmountCents()).isEqualTo(6000);
        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }

    @Test
    void aPartyThatDidNotShrink_movesNoMoney() {
        Reservation reservation = reservation();
        paid(bookingFee());

        assertThat(refund.handBackCoversGivenUp(reservation, 6, 6)).isZero();
        assertThat(refund.handBackCoversGivenUp(reservation, 6, 8)).isZero();
        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }
}
