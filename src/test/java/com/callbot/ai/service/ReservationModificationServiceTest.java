package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.callbot.ai.dto.GuestModificationRequest;
import com.callbot.ai.dto.GuestModificationResponse;
import com.callbot.ai.dto.PublicModificationResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.notification.ReservationModifiedByGuestEvent;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

/**
 * The diner's own hand on their booking.
 *
 * <p>Table geometry lives in {@link TableAvailabilityTest} and the money verdict in
 * {@link PartySizeChangePolicyTest}; both are mocked here. What is tested is what this
 * service alone decides: whether the link is still open, what moves, and what comes back.
 */
@ExtendWith(MockitoExtension.class)
class ReservationModificationServiceTest {

    private static final String TOKEN = "a-modification-token";
    /** Far enough ahead that the service has not happened, whenever the suite runs. */
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.now().plusDays(30);
    private static final OffsetDateTime ENDS_AT = STARTS_AT.plusHours(2);

    @Mock
    private ReservationRepository reservations;
    @Mock
    private RestaurantRepository restaurants;
    @Mock
    private ReservationService reservationService;
    @Mock
    private PartySizeChangePolicy partySizeChangePolicy;
    @Mock
    private PartySizeTopUpService topUps;
    @Mock
    private TableAvailability availability;
    @Mock
    private PartySizeRefund partySizeRefund;
    @Mock
    private ApplicationEventPublisher events;

    private ReservationModificationService service;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID tableId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReservationModificationService(reservations, restaurants,
                reservationService, partySizeChangePolicy, topUps, partySizeRefund,
                availability, events);
    }

    /** A paid table of six, 15,00 € a cover, still a month away. */
    private Reservation reservation() {
        return Reservation.builder()
                .id(reservationId)
                .restaurantId(restaurantId)
                .tableId(tableId)
                .partySize(6)
                .startsAt(STARTS_AT)
                .endsAt(ENDS_AT)
                .status(ReservationStatus.CONFIRMED)
                .modificationToken(TOKEN)
                .modificationWindowHours(0)
                .guaranteeMode(GuaranteeMode.BOOKING_FEE.code())
                .guaranteeStatus(GuaranteeStatus.SECURED)
                .guaranteeCentsPerGuest(1500)
                .guaranteeAmountCents(9000)
                .currency("eur")
                .build();
    }

    private void stored(Reservation reservation) {
        when(reservations.findByModificationToken(TOKEN)).thenReturn(Optional.of(reservation));
        lenient().when(reservations.save(any(Reservation.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(restaurants.findById(restaurantId))
                .thenReturn(Optional.of(Restaurant.builder().id(restaurantId).name("Chez Test").build()));
    }

    private void verdict(PartySizeChange decision) {
        lenient().when(partySizeChangePolicy.decide(any(), any(), any(), any())).thenReturn(decision);
    }

    /** What the shared refund says it handed back, whatever the register holds. */
    private void refundsOnAFall(int cents) {
        lenient().when(partySizeRefund.handBackCoversGivenUp(any(), anyInt(), anyInt()))
                .thenReturn(cents);
    }

    private void roomFor(int partySize, OffsetDateTime startsAt, RestaurantTable table) {
        when(availability.firstSeating(eq(restaurantId), eq(partySize), eq(startsAt), any(), eq(reservationId), any()))
                .thenReturn(table);
    }

    private RestaurantTable table(UUID id) {
        return RestaurantTable.builder().id(id).capacity(8).build();
    }

    private GuestModificationResponse apply(Integer partySize, OffsetDateTime startsAt) {
        return service.apply(TOKEN, new GuestModificationRequest(partySize, startsAt));
    }

    // --- The link itself ------------------------------------------------------

    @Test
    void anUnknownToken_revealsNothingAboutAnyReservation() {
        when(reservations.findByModificationToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.describe("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining("nope");
    }

    @Test
    void theLinkSurvivesUse_soAPartyMayBeChangedTwice() {
        // Unlike the payment link: burning it on the first change would mean sending a
        // fresh one after every one.
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);
        refundsOnAFall(3000);

        apply(5, null);
        apply(4, null);

        assertThat(reservation.getModificationToken()).isEqualTo(TOKEN);
    }

    @Test
    void aCancelledReservation_isNoLongerModifiable() {
        Reservation reservation = reservation();
        reservation.setStatus(ReservationStatus.CANCELLED);
        stored(reservation);

        assertThatThrownBy(() -> apply(4, null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void aServiceAlreadyPast_isNoLongerModifiable() {
        Reservation reservation = reservation();
        reservation.setStartsAt(OffsetDateTime.now().minusHours(1));
        reservation.setEndsAt(OffsetDateTime.now().plusHours(1));
        stored(reservation);

        assertThatThrownBy(() -> apply(4, null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void pastTheWindowTheRestaurateurSet_theLinkOpensOntoNothing() {
        Reservation reservation = reservation();
        reservation.setStartsAt(OffsetDateTime.now().plusHours(2));
        reservation.setEndsAt(OffsetDateTime.now().plusHours(4));
        reservation.setModificationWindowHours(3);
        stored(reservation);

        assertThatThrownBy(() -> apply(4, null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void describe_onACancelledBooking_handsBackNothingButWhoToCall() {
        // The link outlived what it pointed at and goes on being a URL in an old message.
        Reservation reservation = reservation();
        reservation.setStatus(ReservationStatus.CANCELLED);
        stored(reservation);

        PublicModificationResponse view = service.describe(TOKEN);

        assertThat(view.restaurantName()).isEqualTo("Chez Test");
        assertThat(view.open()).isFalse();
        assertThat(view.startsAt()).isNull();
        assertThat(view.partySize()).isNull();
        assertThat(view.centsPerGuest()).isNull();
    }

    @Test
    void describe_onAServiceAlreadyPast_handsBackNothingEither() {
        Reservation reservation = reservation();
        reservation.setStartsAt(OffsetDateTime.now().minusHours(3));
        reservation.setEndsAt(OffsetDateTime.now().minusHours(1));
        stored(reservation);

        assertThat(service.describe(TOKEN).partySize()).isNull();
    }

    @Test
    void describe_saysTheLinkIsClosedRatherThanFailing() {
        // A diner who followed a link deserves a page telling them why, not an error.
        Reservation reservation = reservation();
        reservation.setStartsAt(OffsetDateTime.now().plusHours(2));
        reservation.setEndsAt(OffsetDateTime.now().plusHours(4));
        reservation.setModificationWindowHours(3);
        stored(reservation);

        assertThat(service.describe(TOKEN).open()).isFalse();
    }

    @Test
    void movingIntoTheClosedWindow_isRefused() {
        // Otherwise the window is bypassed by simply booking the change further out and
        // dragging the service into it.
        Reservation reservation = reservation();
        reservation.setModificationWindowHours(3);
        stored(reservation);

        assertThatThrownBy(() -> apply(null, OffsetDateTime.now().plusHours(2)))
                .isInstanceOf(InvalidRequestException.class);
    }

    // --- Moving the time ------------------------------------------------------

    @Test
    void movingTheTime_needsATableFreeOnTheNewSlot() {
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);
        when(availability.firstSeating(any(), anyInt(), any(), any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> apply(null, STARTS_AT.plusHours(1)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void movingTheTime_keepsTheSittingLength() {
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);
        OffsetDateTime later = STARTS_AT.plusHours(1);
        roomFor(6, later, table(tableId));

        apply(null, later);

        assertThat(reservation.getStartsAt()).isEqualTo(later);
        assertThat(reservation.getEndsAt()).isEqualTo(later.plusHours(2));
    }

    @Test
    void movingTheTime_seatsThePartyOnWhateverIsFree() {
        // The room is asked where they go, and they go there: the table they had may well
        // be taken at the new hour.
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);
        UUID otherTable = UUID.randomUUID();
        OffsetDateTime later = STARTS_AT.plusHours(1);
        roomFor(6, later, table(otherTable));

        apply(null, later);

        assertThat(reservation.getTableId()).isEqualTo(otherTable);
    }

    @Test
    void changingOnlyTheParty_leavesTheTableAlone() {
        // Nobody asked to be moved, and the staff laid the room out around where people sit.
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);
        refundsOnAFall(3000);

        apply(4, null);

        assertThat(reservation.getTableId()).isEqualTo(tableId);
        verify(availability, never()).firstSeating(any(), anyInt(), any(), any(), any(), any());
    }

    // --- The party falling ----------------------------------------------------

    // Ce qui revient au convive est teste dans PartySizeRefundTest : la regle
    // appartient au composant partage, pas a ce service.

    // --- The party rising -----------------------------------------------------

    @Test
    void aRiseThatOwesMoney_leavesThePartyWhereItWasAndAsksForTheDifference() {
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.COLLECT_TOP_UP);
        when(topUps.open(reservation, 8)).thenReturn(ReservationCharge.builder()
                .targetPartySize(8).amountCents(3000).currency("eur")
                .paymentToken("top-up-link").build());

        GuestModificationResponse result = apply(8, null);

        assertThat(reservation.getPartySize()).isEqualTo(6);
        assertThat(result.partySize()).isEqualTo(6);
        assertThat(result.pendingTopUp().targetPartySize()).isEqualTo(8);
        // The charge's own link travels with it, so the diner can be walked to the page
        // that settles it rather than told to wait for a message.
        assertThat(result.topUpPaymentToken()).isEqualTo("top-up-link");
    }

    @Test
    void aRiseThatOwesNothing_isAppliedOnTheSpot() {
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);

        assertThat(apply(8, null).partySize()).isEqualTo(8);
        assertThat(reservation.getPartySize()).isEqualTo(8);
    }

    @Test
    void aRiseOwingMoneyWhileTheTimeMoves_movesTheTimeAtTheOldPartySize() {
        // Nothing is held for the larger party: the room only has to hold the party that
        // is actually booked until the difference is settled.
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.COLLECT_TOP_UP);
        when(topUps.open(any(), eq(8))).thenReturn(ReservationCharge.builder()
                .targetPartySize(8).amountCents(3000).currency("eur").build());
        OffsetDateTime later = STARTS_AT.plusHours(1);
        roomFor(6, later, table(tableId));

        apply(8, later);

        assertThat(reservation.getStartsAt()).isEqualTo(later);
        assertThat(reservation.getPartySize()).isEqualTo(6);
    }

    @Test
    void aFallUnderARunningRequest_dropsTheRequestWithIt() {
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY_AND_LAPSE_TOP_UP);
        refundsOnAFall(3000);

        apply(4, null);

        verify(topUps).lapsePendingFor(eq(reservationId), anyString());
    }

    // --- Telling the restaurant -----------------------------------------------

    @Test
    void everyChange_tellsTheRestaurantWhatItWasBefore() {
        // "Six became four" frees a table in someone's head; "table of four" does not.
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);
        refundsOnAFall(3000);

        apply(4, null);

        ArgumentCaptor<ReservationModifiedByGuestEvent> event =
                ArgumentCaptor.forClass(ReservationModifiedByGuestEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().previousPartySize()).isEqualTo(6);
        assertThat(event.getValue().previousStartsAt()).isEqualTo(STARTS_AT);
        assertThat(event.getValue().refundedAmountCents()).isEqualTo(3000);
    }

    @Test
    void achangeThatChangesNothing_announcesNothing() {
        // A diner who opened the page and saved without touching anything has not made
        // news for the dining room.
        Reservation reservation = reservation();
        stored(reservation);
        verdict(PartySizeChange.APPLY);

        apply(6, STARTS_AT);

        verify(events, never()).publishEvent(any(ReservationModifiedByGuestEvent.class));
    }
}
