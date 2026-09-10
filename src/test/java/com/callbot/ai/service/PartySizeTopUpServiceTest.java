package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.dao.DataIntegrityViolationException;

import com.callbot.ai.dto.PublicTopUpResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.stripe.PartySizeTopUpCharge;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Commission;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationTopUpRequestedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class PartySizeTopUpServiceTest {

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
    private ApplicationEventPublisher events;
    @InjectMocks
    private PartySizeTopUpService topUps;

    private final UUID reservationId = UUID.randomUUID();
    private final UUID restaurantId = UUID.randomUUID();

    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2030-01-01T19:00:00Z");
    private static final OffsetDateTime ENDS_AT = OffsetDateTime.parse("2030-01-01T21:00:00Z");

    /** Two guests, sold at 10 € a head when the reservation was taken. */
    private Reservation reservation() {
        return Reservation.builder()
                .id(reservationId)
                .restaurantId(restaurantId)
                .startsAt(STARTS_AT)
                .endsAt(ENDS_AT)
                .partySize(2)
                .status(ReservationStatus.CONFIRMED)
                .guaranteeMode(GuaranteeMode.BOOKING_FEE.code())
                .guaranteeStatus(GuaranteeStatus.SECURED)
                .guaranteeCentsPerGuest(1000)
                .guaranteeAmountCents(2000)
                .currency("eur")
                .build();
    }

    private ReservationCharge savedTopUp() {
        ArgumentCaptor<ReservationCharge> captor = ArgumentCaptor.forClass(ReservationCharge.class);
        verify(charges).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private void savesWhatItIsGiven() {
        when(charges.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void open_pricesTheDifferenceOffTheUnitAmountFrozenAtCreation() {
        savesWhatItIsGiven();

        topUps.open(reservation(), 5);

        ReservationCharge charge = savedTopUp();
        // Three guests added at the price the reservation was sold at, not today's tariff.
        assertThat(charge.getAmountCents()).isEqualTo(3000);
        assertThat(charge.getTargetPartySize()).isEqualTo(5);
        assertThat(charge.getKind()).isEqualTo(ChargeKind.PARTY_SIZE_TOP_UP);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void open_ignoresTheRestaurantsCurrentTariff() {
        // Nothing here reads the restaurant at all: a raised setting cannot reach back
        // into a table already sold.
        savesWhatItIsGiven();

        topUps.open(reservation(), 3);

        assertThat(savedTopUp().getAmountCents()).isEqualTo(1000);
        verify(restaurantRepository, never()).findById(any());
    }

    @Test
    void open_carriesTheSameCommissionAsTheFeeItExtends() {
        savesWhatItIsGiven();

        topUps.open(reservation(), 5);

        assertThat(savedTopUp().getApplicationFeeCents())
                .isEqualTo(Commission.on(3000).amountCents());
    }

    @Test
    void open_issuesItsOwnSingleUseTokenValidForThirtyMinutes() {
        savesWhatItIsGiven();
        Reservation reservation = reservation();
        reservation.setPaymentToken("the-first-payment-token");

        topUps.open(reservation, 5);

        ReservationCharge charge = savedTopUp();
        assertThat(charge.getPaymentToken()).isNotBlank()
                .isNotEqualTo("the-first-payment-token");
        assertThat(charge.getTokenExpiresAt())
                .isCloseTo(OffsetDateTime.now().plusMinutes(30),
                        within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    void open_leavesTheReservationUntouched() {
        savesWhatItIsGiven();
        Reservation reservation = reservation();

        topUps.open(reservation, 5);

        // Confirmed, on its table, at the party it was sold with. Nothing is pre-held.
        assertThat(reservation.getPartySize()).isEqualTo(2);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getGuaranteeStatus()).isEqualTo(GuaranteeStatus.SECURED);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void open_announcesTheRequestSoTheDinerIsTold() {
        savesWhatItIsGiven();

        ReservationCharge charge = topUps.open(reservation(), 5);

        verify(events).publishEvent(
                new ReservationTopUpRequestedEvent(reservationId, charge.getId()));
    }

    @Test
    void open_whenASecondRequestRacesTheFirst_isRefused() {
        // The partial unique index is the last word when two staff members act at once.
        when(charges.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("one pending"));

        assertThatThrownBy(() -> topUps.open(reservation(), 5))
                .isInstanceOf(PartySizeChangeRejectedException.class)
                .hasFieldOrPropertyWithValue("reason", PartySizeChangeRejectedException.TOP_UP_PENDING);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void open_whenTheUnitPricePredatesTheColumn_recoversItFromTheTotal() {
        savesWhatItIsGiven();
        Reservation legacy = reservation();
        legacy.setGuaranteeCentsPerGuest(null);

        topUps.open(legacy, 4);

        assertThat(savedTopUp().getAmountCents()).isEqualTo(2000);
    }

    @Test
    void describe_showsBothPartySizesSoTheRiseDoesNotLookDone() {
        ReservationCharge charge = pendingTopUp(OffsetDateTime.now().plusMinutes(20));
        when(charges.findByPaymentToken("tok")).thenReturn(Optional.of(charge));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation()));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        PublicTopUpResponse response = topUps.describe("tok");

        assertThat(response.currentPartySize()).isEqualTo(2);
        assertThat(response.targetPartySize()).isEqualTo(5);
        assertThat(response.amountCents()).isEqualTo(3000);
        assertThat(response.status()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void describe_whenTheDeadlineHasPassed_reportsTheLinkClosed() {
        ReservationCharge charge = pendingTopUp(OffsetDateTime.now().minusMinutes(1));
        when(charges.findByPaymentToken("tok")).thenReturn(Optional.of(charge));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation()));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        assertThat(topUps.describe("tok").status()).isEqualTo("closed");
    }

    @Test
    void describe_whenTheTokenBelongsToAnotherKindOfCharge_findsNothing() {
        // The booking fee's own money must not be reachable through the top-up route.
        ReservationCharge bookingFee = ReservationCharge.builder()
                .id(UUID.randomUUID()).reservationId(reservationId)
                .kind(ChargeKind.BOOKING_FEE).status(ChargeStatus.PENDING)
                .amountCents(2000).currency("eur").paymentToken("tok").build();
        when(charges.findByPaymentToken("tok")).thenReturn(Optional.of(bookingFee));

        assertThatThrownBy(() -> topUps.describe("tok"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void startCheckout_expiresTheOldSessionAndOpensAFreshOne() {
        ReservationCharge charge = pendingTopUp(OffsetDateTime.now().plusMinutes(20));
        charge.setStripeSessionId("cs_previous");
        when(charges.findByPaymentToken("tok")).thenReturn(Optional.of(charge));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation()));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));
        when(connect.createPartySizeTopUpCheckout(any()))
                .thenReturn(new CheckoutSession("cs_new", "https://stripe.test/cs_new"));

        assertThat(topUps.startCheckout("tok").url()).isEqualTo("https://stripe.test/cs_new");

        // Two live sessions would both be payable, for one table.
        verify(connect).expireCheckout("cs_previous");

        ArgumentCaptor<PartySizeTopUpCharge> sent =
                ArgumentCaptor.forClass(PartySizeTopUpCharge.class);
        verify(connect).createPartySizeTopUpCheckout(sent.capture());
        assertThat(sent.getValue().extraGuests()).isEqualTo(3);
        assertThat(sent.getValue().amountCents()).isEqualTo(3000);
        assertThat(charge.getStripeSessionId()).isEqualTo("cs_new");
    }

    @Test
    void startCheckout_whenTheDeadlineHasPassed_refusesToOpenAPage() {
        ReservationCharge charge = pendingTopUp(OffsetDateTime.now().minusMinutes(1));
        when(charges.findByPaymentToken("tok")).thenReturn(Optional.of(charge));

        assertThatThrownBy(() -> topUps.startCheckout("tok"))
                .isInstanceOf(InvalidRequestException.class);

        verify(connect, never()).createPartySizeTopUpCheckout(any());
    }

    @Test
    void markPaid_recordsTheMoneyAndBurnsTheLink() {
        ReservationCharge charge = pendingTopUp(OffsetDateTime.now().plusMinutes(20));
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation()));

        topUps.markPaid(charge, "pi_123");

        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(charge.getStripePaymentIntentId()).isEqualTo("pi_123");
        assertThat(charge.getPaymentToken()).isNull();
        // Nothing leaves for the restaurateur's bank before the day after the service.
        assertThat(charge.getPayoutEligibleAt()).isEqualTo(ENDS_AT.plusDays(1));
    }

    @Test
    void markPaid_whenStripeRedeliversTheSameEvent_changesNothing() {
        ReservationCharge charge = pendingTopUp(OffsetDateTime.now().plusMinutes(20));
        charge.setStatus(ChargeStatus.PAID);
        charge.setStripePaymentIntentId("pi_first");

        topUps.markPaid(charge, "pi_second");

        assertThat(charge.getStripePaymentIntentId()).isEqualTo("pi_first");
        verify(charges, never()).save(any());
    }

    private ReservationCharge pendingTopUp(OffsetDateTime expiresAt) {
        return ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .kind(ChargeKind.PARTY_SIZE_TOP_UP)
                .status(ChargeStatus.PENDING)
                .amountCents(3000)
                .applicationFeeCents(Commission.on(3000).amountCents())
                .currency("eur")
                .paymentToken("tok")
                .tokenExpiresAt(expiresAt)
                .targetPartySize(5)
                .build();
    }

    private Restaurant restaurant() {
        return Restaurant.builder()
                .id(restaurantId)
                .name("Chez Hikky")
                .stripeAccountId("acct_123")
                .build();
    }

    private static org.assertj.core.data.TemporalUnitOffset within(long amount,
            java.time.temporal.TemporalUnit unit) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(amount, unit);
    }
}
