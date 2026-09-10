package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.callbot.ai.dto.CancellationResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.stripe.BookingFeeCharge;
import com.callbot.ai.gateway.stripe.ConnectWebhookEvent;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationCancelledByGuestEvent;
import com.callbot.ai.notification.ReservationConfirmedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class ReservationPaymentServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationChargeRepository charges;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private StripeConnectGateway connect;
    @Mock
    private ConnectAccountService connectAccount;
    @Mock
    private PartySizeTopUpService topUps;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private ReservationPaymentService service;

    private static final String PAYMENT_TOKEN = "pay-token";
    private static final String CANCELLATION_TOKEN = "cancel-token";
    private static final String ACCOUNT = "acct_123";

    private final UUID reservationId = UUID.randomUUID();
    private final UUID restaurantId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    private Reservation awaitingPayment() {
        return Reservation.builder()
                .id(reservationId)
                .restaurantId(restaurantId)
                .partySize(6)
                .startsAt(OffsetDateTime.now().plusDays(10))
                .endsAt(OffsetDateTime.now().plusDays(10).plusHours(2))
                .status(ReservationStatus.AWAITING_PAYMENT)
                .guaranteeMode(GuaranteeMode.BOOKING_FEE.code())
                .guaranteeStatus(GuaranteeStatus.AWAITING)
                .guaranteeAmountCents(9000)
                .guaranteeRefundWindowHours(48)
                .currency("eur")
                .paymentToken(PAYMENT_TOKEN)
                .cancellationToken(CANCELLATION_TOKEN)
                .guaranteeExpiresAt(OffsetDateTime.now().plusMinutes(20))
                .build();
    }

    /** Captures the charge the service wrote to the register. */
    private ReservationCharge savedCharge() {
        ArgumentCaptor<ReservationCharge> captor = ArgumentCaptor.forClass(ReservationCharge.class);
        verify(charges).save(captor.capture());
        return captor.getValue();
    }

    /** A party that grew and was paid for: a second settled charge on the same booking. */
    private ReservationCharge settledTopUp(String paymentIntentId) {
        return ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.PARTY_SIZE_TOP_UP)
                .status(ChargeStatus.PAID)
                .amountCents(3000)
                .applicationFeeCents(200)
                .currency("eur")
                .stripePaymentIntentId(paymentIntentId)
                .paidAt(OffsetDateTime.now())
                .targetPartySize(5)
                .build();
    }

    private ReservationCharge settledBookingFee(String paymentIntentId) {
        return ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.BOOKING_FEE)
                .status(ChargeStatus.PAID)
                .amountCents(9000)
                .applicationFeeCents(500)
                .currency("eur")
                .stripePaymentIntentId(paymentIntentId)
                .paidAt(OffsetDateTime.now())
                .build();
    }

    private void restaurantExists() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(
                Restaurant.builder().id(restaurantId).name("Chez Payant")
                        .organizationId(organizationId).build()));
    }

    /** The account lives on the restaurant, so one stub covers both. */
    private void restaurantWithAPaymentAccount() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(
                Restaurant.builder().id(restaurantId).name("Chez Payant")
                        .organizationId(organizationId).stripeAccountId(ACCOUNT)
                        .stripeChargesEnabled(true).build()));
    }

    @Test
    void checkoutHandsStripeTheAmountTheCommissionAndTheRestaurantsAccount() {
        Reservation reservation = awaitingPayment();
        when(reservationRepository.findByPaymentToken(PAYMENT_TOKEN)).thenReturn(Optional.of(reservation));
        restaurantWithAPaymentAccount();
        when(connect.createBookingFeeCheckout(any()))
                .thenReturn(new CheckoutSession("cs_1", "https://checkout.stripe.com/cs_1"));

        assertThat(service.startCheckout(PAYMENT_TOKEN).url()).isEqualTo("https://checkout.stripe.com/cs_1");

        ArgumentCaptor<BookingFeeCharge> charge = ArgumentCaptor.forClass(BookingFeeCharge.class);
        verify(connect).createBookingFeeCheckout(charge.capture());
        assertThat(charge.getValue().amountCents()).isEqualTo(9000);
        assertThat(charge.getValue().applicationFeeCents()).isEqualTo(500);
        assertThat(charge.getValue().connectedAccountId()).isEqualTo(ACCOUNT);
        assertThat(charge.getValue().reservationId()).isEqualTo(reservationId);
        ReservationCharge opened = savedCharge();
        assertThat(opened.getStripeSessionId()).isEqualTo("cs_1");
        assertThat(opened.getApplicationFeeCents()).isEqualTo(500);
        assertThat(opened.getAmountCents()).isEqualTo(9000);
        assertThat(opened.getKind()).isEqualTo(ChargeKind.BOOKING_FEE);
        assertThat(opened.getStatus()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void checkoutIsRefusedOnceThePaymentWindowHasClosed() {
        Reservation reservation = awaitingPayment();
        reservation.setGuaranteeExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(reservationRepository.findByPaymentToken(PAYMENT_TOKEN)).thenReturn(Optional.of(reservation));
        restaurantExists();

        assertThatThrownBy(() -> service.startCheckout(PAYMENT_TOKEN))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("délai");

        verify(connect, never()).createBookingFeeCheckout(any());
    }

    @Test
    void checkoutIsRefusedForAReservationThatOwesNothing() {
        Reservation reservation = awaitingPayment();
        reservation.setGuaranteeMode(GuaranteeMode.NONE.code());
        when(reservationRepository.findByPaymentToken(PAYMENT_TOKEN)).thenReturn(Optional.of(reservation));
        restaurantExists();

        assertThatThrownBy(() -> service.startCheckout(PAYMENT_TOKEN))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void paymentConfirmsTheReservationAndSchedulesTheMoneyForTheDayAfterTheService() {
        Reservation reservation = awaitingPayment();
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        service.markPaid(new ConnectWebhookEvent.ReservationPaid(reservationId, "cs_1", "pi_1", 9000));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.SECURED);
        ReservationCharge settled = savedCharge();
        assertThat(settled.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(settled.getStripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(settled.getAmountCents()).isEqualTo(9000);
        assertThat(settled.getApplicationFeeCents()).isEqualTo(500);
        assertThat(settled.getPaidAt()).isNotNull();
        assertThat(settled.getPayoutEligibleAt()).isEqualTo(reservation.getEndsAt().plusDays(1));
        // Single use: the link must not reopen a checkout for a table already paid for.
        assertThat(reservation.getPaymentToken()).isNull();
        verify(events).publishEvent(new ReservationConfirmedEvent(reservationId));
    }

    @Test
    void aWebhookDeliveredTwiceConfirmsOnlyOnce() {
        Reservation reservation = awaitingPayment();
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(charges.findByReservationId(reservationId))
                .thenReturn(List.of(settledBookingFee("pi_1")));

        // Same payment intent: Stripe redelivering, not a second payment.
        service.markPaid(new ConnectWebhookEvent.ReservationPaid(reservationId, "cs_1", "pi_1", 9000));

        verify(events, never()).publishEvent(any(ReservationConfirmedEvent.class));
        verify(reservationRepository, never()).save(any());
        verify(charges, never()).save(any());
        verify(connect, never()).refundFully(anyString(), anyString());
    }

    @Test
    void aPaymentLandingAfterTheTableWasReleasedIsNotTurnedIntoAConfirmation() {
        Reservation reservation = awaitingPayment();
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setGuaranteeStatus(GuaranteeStatus.EXPIRED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        service.markPaid(new ConnectWebhookEvent.ReservationPaid(reservationId, "cs_1", "pi_1", 9000));

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(events, never()).publishEvent(any(ReservationConfirmedEvent.class));
    }

    @Test
    void cancellingWellBeforeTheServiceGivesEveryCentBack() {
        Reservation reservation = paidReservationStartingIn(72);
        ReservationCharge refundedCharge = settledBookingFee("pi_1");
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));
        when(charges.findByReservationIdAndStatus(reservationId, ChargeStatus.PAID))
                .thenReturn(List.of(refundedCharge));

        CancellationResponse response = service.cancelByToken(CANCELLATION_TOKEN);

        assertThat(response.refunded()).isTrue();
        assertThat(response.refundedAmountCents()).isEqualTo(9000);
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.REFUNDED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(connect).refundFully("pi_1", "refund-" + refundedCharge.getId());
        verify(events).publishEvent(
                new ReservationCancelledByGuestEvent(reservationId, true, 9000));
    }

    @Test
    void cancellingEndsAnyRequestForALargerPartyAlongWithIt() {
        // A request outstanding collected nothing, so there is nothing to give back —
        // only a live link to close before it buys guests at a service that is off.
        Reservation reservation = paidReservationStartingIn(72);
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));
        when(charges.findByReservationIdAndStatus(reservationId, ChargeStatus.PAID))
                .thenReturn(List.of());

        service.cancelByToken(CANCELLATION_TOKEN);

        verify(topUps).lapsePendingFor(reservationId, "the diner cancelled the reservation");
    }

    @Test
    void cancellingRefundsASettledTopUpWithTheRestAndNothingSpecial() {
        // A top-up already paid is money in the register like any other. The refund path
        // gives back every settled charge, so it needs no handling of its own.
        Reservation reservation = paidReservationStartingIn(72);
        ReservationCharge fee = settledBookingFee("pi_fee");
        ReservationCharge topUp = settledTopUp("pi_top_up");
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));
        when(charges.findByReservationIdAndStatus(reservationId, ChargeStatus.PAID))
                .thenReturn(List.of(fee, topUp));

        CancellationResponse response = service.cancelByToken(CANCELLATION_TOKEN);

        verify(connect).refundFully("pi_fee", "refund-" + fee.getId());
        verify(connect).refundFully("pi_top_up", "refund-" + topUp.getId());
        assertThat(topUp.getStatus()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(response.refundedAmountCents()).isEqualTo(9000 + 3000);
    }

    @Test
    void cancellingInsideTheWindowRefundsNothing() {
        Reservation reservation = paidReservationStartingIn(12);
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));

        CancellationResponse response = service.cancelByToken(CANCELLATION_TOKEN);

        assertThat(response.cancelled()).isTrue();
        assertThat(response.refunded()).isFalse();
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.SECURED);
        verify(connect, never()).refundFully(anyString(), anyString());
        verify(events).publishEvent(
                new ReservationCancelledByGuestEvent(reservationId, false, 0));
    }

    @Test
    void theWindowFrozenOnTheReservationWinsOverTheRestaurantsCurrentSetting() {
        // Booked under a 48 h promise, service in 36 h: the restaurateur may since have
        // moved to 72 h, but this diner keeps what they were told.
        Reservation reservation = paidReservationStartingIn(36);
        reservation.setGuaranteeRefundWindowHours(24);
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));
        when(charges.findByReservationIdAndStatus(reservationId, ChargeStatus.PAID))
                .thenReturn(List.of(settledBookingFee("pi_1")));

        assertThat(service.cancelByToken(CANCELLATION_TOKEN).refunded()).isTrue();
    }

    @Test
    void aSecondSessionCannotBeOpenedWithoutClosingTheFirst() {
        Reservation reservation = awaitingPayment();
        ReservationCharge open = ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.BOOKING_FEE)
                .status(ChargeStatus.PENDING)
                .amountCents(9000)
                .currency("eur")
                .stripeSessionId("cs_old")
                .build();
        when(reservationRepository.findByPaymentToken(PAYMENT_TOKEN)).thenReturn(Optional.of(reservation));
        when(charges.findByReservationIdAndKindAndStatus(
                reservationId, ChargeKind.BOOKING_FEE, ChargeStatus.PENDING))
                .thenReturn(Optional.of(open));
        restaurantWithAPaymentAccount();
        when(connect.createBookingFeeCheckout(any()))
                .thenReturn(new CheckoutSession("cs_new", "https://checkout.stripe.com/cs_new"));

        service.startCheckout(PAYMENT_TOKEN);

        // Two payable sessions would mean two possible payments for one table.
        verify(connect).expireCheckout("cs_old");
        // Reopened, not added to: the diner still owes exactly one booking fee.
        assertThat(savedCharge().getId()).isEqualTo(open.getId());
        assertThat(open.getStripeSessionId()).isEqualTo("cs_new");
    }

    @Test
    void aGenuineSecondPaymentIsGivenBackRatherThanSilentlyKept() {
        Reservation reservation = awaitingPayment();
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        ReservationCharge first = settledBookingFee("pi_first");
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(charges.findByReservationId(reservationId)).thenReturn(List.of(first));

        service.markPaid(new ConnectWebhookEvent.ReservationPaid(reservationId, "cs_2", "pi_second", 9000));

        verify(connect).refundFully("pi_second", "duplicate-pi_second");
        // The register keeps the payment that actually stands.
        assertThat(first.getStripePaymentIntentId()).isEqualTo("pi_first");
        verify(charges, never()).save(any());
    }

    @Test
    void theCancellationLinkStopsWorkingOnceTheServiceHasHappened() {
        Reservation reservation = paidReservationStartingIn(72);
        reservation.setStartsAt(OffsetDateTime.now().minusHours(2));
        reservation.setEndsAt(OffsetDateTime.now());
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.cancelByToken(CANCELLATION_TOKEN))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("service");

        // Nothing is rewritten about a table that was in fact used.
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(connect, never()).refundFully(anyString(), anyString());
    }

    @Test
    void cancellingTwiceDoesNotRefundTwice() {
        Reservation reservation = paidReservationStartingIn(72);
        reservation.setStatus(ReservationStatus.CANCELLED);
        ReservationCharge given = settledBookingFee("pi_1");
        given.setStatus(ChargeStatus.REFUNDED);
        given.setRefundedAt(OffsetDateTime.now());
        given.setRefundedAmountCents(9000);
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));
        when(charges.findByReservationIdAndStatus(reservationId, ChargeStatus.REFUNDED))
                .thenReturn(List.of(given));

        CancellationResponse response = service.cancelByToken(CANCELLATION_TOKEN);

        assertThat(response.refunded()).isTrue();
        verify(connect, never()).refundFully(anyString(), anyString());
        verify(events, never()).publishEvent(any(ReservationCancelledByGuestEvent.class));
    }

    private Reservation paidReservationStartingIn(int hours) {
        Reservation reservation = awaitingPayment();
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        reservation.setStartsAt(OffsetDateTime.now().plusHours(hours));
        reservation.setEndsAt(OffsetDateTime.now().plusHours(hours + 2));
        return reservation;
    }
}
