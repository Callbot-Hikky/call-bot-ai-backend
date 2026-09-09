package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationCancelledByGuestEvent;
import com.callbot.ai.notification.ReservationConfirmedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class ReservationPaymentServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private StripeConnectGateway connect;
    @Mock
    private ConnectAccountService connectAccount;
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

    private void restaurantExists() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(
                Restaurant.builder().id(restaurantId).name("Chez Payant")
                        .organizationId(organizationId).build()));
    }

    private void restaurantAndOrganizationExist() {
        restaurantExists();
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(
                Organization.builder().id(organizationId).stripeAccountId(ACCOUNT)
                        .stripeChargesEnabled(true).build()));
    }

    @Test
    void checkoutHandsStripeTheAmountTheCommissionAndTheRestaurantsAccount() {
        Reservation reservation = awaitingPayment();
        when(reservationRepository.findByPaymentToken(PAYMENT_TOKEN)).thenReturn(Optional.of(reservation));
        restaurantAndOrganizationExist();
        when(connect.createBookingFeeCheckout(any()))
                .thenReturn(new CheckoutSession("cs_1", "https://checkout.stripe.com/cs_1"));

        assertThat(service.startCheckout(PAYMENT_TOKEN).url()).isEqualTo("https://checkout.stripe.com/cs_1");

        ArgumentCaptor<BookingFeeCharge> charge = ArgumentCaptor.forClass(BookingFeeCharge.class);
        verify(connect).createBookingFeeCheckout(charge.capture());
        assertThat(charge.getValue().amountCents()).isEqualTo(9000);
        assertThat(charge.getValue().applicationFeeCents()).isEqualTo(500);
        assertThat(charge.getValue().connectedAccountId()).isEqualTo(ACCOUNT);
        assertThat(charge.getValue().reservationId()).isEqualTo(reservationId);
        assertThat(reservation.getStripeSessionId()).isEqualTo("cs_1");
        assertThat(reservation.getApplicationFeeCents()).isEqualTo(500);
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
        assertThat(reservation.getStripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(reservation.getPaidAt()).isNotNull();
        assertThat(reservation.getPayoutEligibleAt()).isEqualTo(reservation.getEndsAt().plusDays(1));
        // Single use: the link must not reopen a checkout for a table already paid for.
        assertThat(reservation.getPaymentToken()).isNull();
        verify(events).publishEvent(new ReservationConfirmedEvent(reservationId));
    }

    @Test
    void aWebhookDeliveredTwiceConfirmsOnlyOnce() {
        Reservation reservation = awaitingPayment();
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        service.markPaid(new ConnectWebhookEvent.ReservationPaid(reservationId, "cs_1", "pi_1", 9000));

        verify(events, never()).publishEvent(any(ReservationConfirmedEvent.class));
        verify(reservationRepository, never()).save(any());
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
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));

        CancellationResponse response = service.cancelByToken(CANCELLATION_TOKEN);

        assertThat(response.refunded()).isTrue();
        assertThat(response.refundedAmountCents()).isEqualTo(9000);
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.REFUNDED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        verify(connect).refundFully("pi_1");
        verify(events).publishEvent(new ReservationCancelledByGuestEvent(reservationId, true));
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
        verify(connect, never()).refundFully(anyString());
        verify(events).publishEvent(new ReservationCancelledByGuestEvent(reservationId, false));
    }

    @Test
    void theWindowFrozenOnTheReservationWinsOverTheRestaurantsCurrentSetting() {
        // Booked under a 48 h promise, service in 36 h: the restaurateur may since have
        // moved to 72 h, but this diner keeps what they were told.
        Reservation reservation = paidReservationStartingIn(36);
        reservation.setGuaranteeRefundWindowHours(24);
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));

        assertThat(service.cancelByToken(CANCELLATION_TOKEN).refunded()).isTrue();
    }

    @Test
    void cancellingTwiceDoesNotRefundTwice() {
        Reservation reservation = paidReservationStartingIn(72);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setRefundedAt(OffsetDateTime.now());
        reservation.setRefundedAmountCents(9000);
        when(reservationRepository.findByCancellationToken(CANCELLATION_TOKEN))
                .thenReturn(Optional.of(reservation));

        CancellationResponse response = service.cancelByToken(CANCELLATION_TOKEN);

        assertThat(response.refunded()).isTrue();
        verify(connect, never()).refundFully(anyString());
        verify(events, never()).publishEvent(any(ReservationCancelledByGuestEvent.class));
    }

    private Reservation paidReservationStartingIn(int hours) {
        Reservation reservation = awaitingPayment();
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setGuaranteeStatus(GuaranteeStatus.SECURED);
        reservation.setStripePaymentIntentId("pi_1");
        reservation.setStartsAt(OffsetDateTime.now().plusHours(hours));
        reservation.setEndsAt(OffsetDateTime.now().plusHours(hours + 2));
        return reservation;
    }
}
