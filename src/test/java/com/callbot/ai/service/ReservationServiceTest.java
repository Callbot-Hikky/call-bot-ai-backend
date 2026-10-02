package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.PartySizeChangeRejectedException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantHoursRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;
import com.callbot.ai.notification.ReservationUpdatedEvent;
import com.callbot.ai.security.OrganizationScope;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private RestaurantTableRepository tableRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantHoursRepository hoursRepository;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private OrganizationScope scope;
    @Mock
    private GuaranteePolicy guaranteePolicy;
    @Mock
    private PartySizeChangePolicy partySizeChangePolicy;
    @Mock
    private PartySizeTopUpService topUps;
    @Mock
    private PartySizeRefund partySizeRefund;
    @Mock
    private ReservationChargeRepository charges;
    @InjectMocks
    private ReservationService reservationService;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID otherOrganizationId = UUID.randomUUID();

    private static final String OWNER = "owner@resto.fr";
    private static final String INTRUDER = "intruder@autre-resto.fr";
    /** The AI microservice authenticates with an API key: no user, no organization. */
    private static final String SERVICE_CALLER = null;

    private ReservationRequest request() {
        return new ReservationRequest(restaurantId, null, null, null,
                OffsetDateTime.parse("2030-01-01T19:00:00Z"),
                OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                2, null, null, null, null);
    }

    private Restaurant restaurant() {
        return Restaurant.builder().id(restaurantId).organizationId(organizationId).build();
    }

    private Reservation reservation(UUID id) {
        return Reservation.builder().id(id).restaurantId(restaurantId).partySize(2).build();
    }

    @Test
    void create_whenRestaurantExists_appliesDefaultStatusAndSource() {
        when(scope.ownedRestaurant(restaurantId, OWNER)).thenReturn(restaurant());
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ReservationResponse response = reservationService.create(request(), OWNER);

        assertThat(response.partySize()).isEqualTo(2);
        assertThat(response.status()).isEqualTo("pending");
        assertThat(response.source()).isEqualTo("callbot");
    }

    @Test
    void create_whenRestaurantMissing_throwsAndDoesNotSave() {
        when(scope.ownedRestaurant(restaurantId, OWNER))
                .thenThrow(new ResourceNotFoundException("Restaurant", restaurantId));

        assertThatThrownBy(() -> reservationService.create(request(), OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void create_forRestaurantOfAnotherOrganization_throwsAndDoesNotSave() {
        when(scope.ownedRestaurant(restaurantId, INTRUDER))
                .thenThrow(new ResourceNotFoundException("Restaurant", restaurantId));

        assertThatThrownBy(() -> reservationService.create(request(), INTRUDER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.get(id, Set.of(), OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_whenReservationBelongsToAnotherOrganization_reportsItMissing() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));
        doThrow(new ResourceNotFoundException("Reservation", id))
                .when(scope).requireOwnedThrough(restaurantId, "Reservation", id, INTRUDER);

        assertThatThrownBy(() -> reservationService.get(id, Set.of(), INTRUDER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_whenReservationBelongsToCallerOrganization_returnsIt() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));

        assertThat(reservationService.get(id, Set.of(), OWNER).partySize()).isEqualTo(2);
    }

    @Test
    void delete_whenMissing_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.delete(id, OWNER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reservationRepository, never()).deleteById(any());
    }

    @Test
    void delete_whenReservationBelongsToAnotherOrganization_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));
        doThrow(new ResourceNotFoundException("Reservation", id))
                .when(scope).requireOwnedThrough(restaurantId, "Reservation", id, INTRUDER);

        assertThatThrownBy(() -> reservationService.delete(id, INTRUDER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reservationRepository, never()).deleteById(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void delete_cancelsTheReservationInsteadOfErasingIt() {
        UUID id = UUID.randomUUID();
        Reservation reservation = reservation(id);
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));

        reservationService.delete(id, OWNER);

        // A reservation can carry a payment; erasing the row would erase its trace.
        verify(reservationRepository, never()).deleteById(any());
        verify(reservationRepository).save(reservation);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isNotNull();
    }

    @Test
    void delete_whenAlreadyCancelled_doesNothing() {
        UUID id = UUID.randomUUID();
        Reservation reservation = reservation(id);
        reservation.setStatus(ReservationStatus.CANCELLED);
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));

        reservationService.delete(id, OWNER);

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void create_whenServiceCallerAsksForAnExemption_isRefused() {
        // Waiving a guarantee has to be attributable to a person.
        when(scope.ownedRestaurant(restaurantId, SERVICE_CALLER)).thenReturn(restaurant());
        when(scope.userIdOf(SERVICE_CALLER)).thenReturn(Optional.empty());

        ReservationRequest exempting = new ReservationRequest(restaurantId, null, null, null,
                OffsetDateTime.parse("2030-01-01T19:00:00Z"),
                OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                2, null, null, null, true);

        assertThatThrownBy(() -> reservationService.create(exempting, SERVICE_CALLER))
                .isInstanceOf(InvalidRequestException.class);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void list_withRestaurantId_filters() {
        when(reservationRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());

        reservationService.list(restaurantId, Set.of(), OWNER);

        verify(reservationRepository).findByRestaurantId(restaurantId);
        verify(reservationRepository, never()).findAll();
    }

    @Test
    void list_withRestaurantIdOfAnotherOrganization_throws() {
        doThrow(new ResourceNotFoundException("Restaurant", restaurantId))
                .when(scope).requireOwnedRestaurant(restaurantId, INTRUDER);

        assertThatThrownBy(() -> reservationService.list(restaurantId, Set.of(), INTRUDER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reservationRepository, never()).findByRestaurantId(any());
    }

    @Test
    void list_withoutRestaurantId_scopesToCallerOrganization() {
        when(scope.ownedRestaurantIds(OWNER)).thenReturn(Optional.of(List.of(restaurantId)));
        when(reservationRepository.findByRestaurantIdIn(List.of(restaurantId))).thenReturn(List.of());

        reservationService.list(null, Set.of(), OWNER);

        verify(reservationRepository).findByRestaurantIdIn(List.of(restaurantId));
        verify(reservationRepository, never()).findAll();
    }

    @Test
    void list_withoutRestaurantId_whenOrganizationHasNoRestaurant_returnsEmpty() {
        when(scope.ownedRestaurantIds(OWNER)).thenReturn(Optional.of(List.of()));

        assertThat(reservationService.list(null, Set.of(), OWNER)).isEmpty();
        verify(reservationRepository, never()).findAll();
    }

    @Test
    void list_forServiceCaller_isNotScopedToAnyOrganization() {
        // No organization: the AI microservice, which legitimately serves every restaurant.
        when(scope.ownedRestaurantIds(SERVICE_CALLER)).thenReturn(Optional.empty());
        when(reservationRepository.findAll()).thenReturn(List.of());

        reservationService.list(null, Set.of(), SERVICE_CALLER);

        verify(reservationRepository).findAll();
    }

    private ReservationRequest raisedTo(int partySize) {
        return new ReservationRequest(restaurantId, null, null, null,
                OffsetDateTime.parse("2030-01-01T19:00:00Z"),
                OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                partySize, null, null, null, null);
    }

    @Test
    void update_whenPartySizeChanges_weighsTheRuleBeforeWriting() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partySizeChangePolicy.decide(any(), any(), any(), any()))
                .thenReturn(PartySizeChange.APPLY);

        ReservationResponse response = reservationService.update(id, raisedTo(6), false, OWNER);

        assertThat(response.partySize()).isEqualTo(6);
        verify(partySizeChangePolicy).decide(any(), eq(6),
                eq(OffsetDateTime.parse("2030-01-01T19:00:00Z")),
                eq(OffsetDateTime.parse("2030-01-01T21:00:00Z")));
    }

    @Test
    void update_whenThePartyShrinks_handsBackTheCoversGivenUp() {
        // The same event as a diner shrinking their own party from their link. Answering
        // the two differently would make the refund depend on which door it came through.
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partySizeChangePolicy.decide(any(), any(), any(), any()))
                .thenReturn(PartySizeChange.APPLY);

        reservationService.update(id, raisedTo(1), false, OWNER);

        verify(partySizeRefund).handBackCoversGivenUp(any(), eq(2), eq(1));
    }

    @Test
    void update_whenARiseStillOwesMoney_handsNothingBack() {
        // Nothing has changed yet, so there is nothing to give back.
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partySizeChangePolicy.decide(any(), any(), any(), any()))
                .thenReturn(PartySizeChange.COLLECT_TOP_UP);
        when(topUps.open(any(), eq(6))).thenReturn(ReservationCharge.builder()
                .targetPartySize(6).amountCents(6000).currency("eur").build());

        reservationService.update(id, raisedTo(6), false, OWNER);

        verify(partySizeRefund, never()).handBackCoversGivenUp(any(), anyInt(), anyInt());
    }

    @Test
    void update_whenTheRuleRejectsTheChange_writesNothing() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));
        doThrow(new PartySizeChangeRejectedException(
                PartySizeChangeRejectedException.TOP_UP_PENDING, "already running"))
                .when(partySizeChangePolicy).decide(any(), any(), any(), any());

        assertThatThrownBy(() -> reservationService.update(id, request(), false, OWNER))
                .isInstanceOf(PartySizeChangeRejectedException.class);

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void update_whenTheRiseHasToBePaidFor_keepsTheCoversAndOpensATopUp() {
        UUID id = UUID.randomUUID();
        Reservation stored = reservation(id);
        when(reservationRepository.findById(id)).thenReturn(Optional.of(stored));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partySizeChangePolicy.decide(any(), any(), any(), any()))
                .thenReturn(PartySizeChange.COLLECT_TOP_UP);
        when(topUps.open(any(), eq(6))).thenReturn(ReservationCharge.builder()
                .id(UUID.randomUUID())
                .reservationId(id)
                .kind(ChargeKind.PARTY_SIZE_TOP_UP)
                .status(ChargeStatus.PENDING)
                .amountCents(2000)
                .currency("eur")
                .targetPartySize(6)
                .tokenExpiresAt(OffsetDateTime.parse("2030-01-01T18:30:00Z"))
                .build());

        ReservationResponse response = reservationService.update(id, raisedTo(6), false, OWNER);

        // The table is not sold before it is paid for: the party stays where it was.
        assertThat(response.partySize()).isEqualTo(2);
        assertThat(response.pendingTopUp()).isNotNull();
        assertThat(response.pendingTopUp().targetPartySize()).isEqualTo(6);
        assertThat(response.pendingTopUp().amountCents()).isEqualTo(2000);
        // The diner hears about the money owed, not about a change that has not happened.
        verify(events, never()).publishEvent(any(ReservationUpdatedEvent.class));
    }

    @Test
    void update_whenTheRiseHasToBePaidFor_stillWritesTheRestOfTheEdit() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation(id)));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(partySizeChangePolicy.decide(any(), any(), any(), any()))
                .thenReturn(PartySizeChange.COLLECT_TOP_UP);
        when(topUps.open(any(), eq(6))).thenReturn(ReservationCharge.builder()
                .id(UUID.randomUUID()).reservationId(id).kind(ChargeKind.PARTY_SIZE_TOP_UP)
                .status(ChargeStatus.PENDING).amountCents(2000).currency("eur")
                .targetPartySize(6).build());

        ReservationRequest withNote = new ReservationRequest(restaurantId, null, null, null,
                OffsetDateTime.parse("2030-01-01T19:00:00Z"),
                OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                6, null, null, "Allergie arachide", null);

        ReservationResponse response = reservationService.update(id, withNote, false, OWNER);

        // A staff member correcting a note alongside the covers must not lose the note.
        assertThat(response.notes()).isEqualTo("Allergie arachide");
        assertThat(response.partySize()).isEqualTo(2);
    }

    // --- creneaux : la replanification et la reservation en ligne partagent le meme calcul

    @Test
    void slotsFor_givesTheSameDaysAsRescheduleSlots() {
        Restaurant restaurant = Restaurant.builder().id(restaurantId).name("Chez Test")
                .phoneNumber("+33100000000").timezone("Europe/Paris").locale("fr").build();
        RestaurantTable table = RestaurantTable.builder().id(UUID.randomUUID()).restaurantId(restaurantId)
                .name("T4").capacity(4).isActive(true).build();
        UUID reservationId = UUID.randomUUID();
        Reservation existing = Reservation.builder().id(reservationId).restaurantId(restaurantId)
                .startsAt(OffsetDateTime.parse("2030-01-01T19:00:00Z"))
                .endsAt(OffsetDateTime.parse("2030-01-01T20:30:00Z")).partySize(2).build();
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(existing));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of(table));
        when(reservationRepository.findBusyTableIdsExcluding(any(), any(), any(), any())).thenReturn(List.of());
        // Demain : un jour entier dans le futur, quel que soit le moment ou le test tourne.
        LocalDate from = LocalDate.now(java.time.ZoneId.of("Europe/Paris")).plusDays(1);

        RescheduleSlotsResponse viaReschedule = reservationService.rescheduleSlots(reservationId, from, null, OWNER);
        RescheduleSlotsResponse direct = reservationService.slotsFor(restaurant, from, 2,
                Duration.ofMinutes(90), reservationId);

        assertThat(direct.days()).hasSize(BookingPolicy.WINDOW_DAYS);
        assertThat(direct).isEqualTo(viaReschedule);
        // Sans horaires configures : ouvert 11h-23h, un creneau toutes les 30 min, dernier a 21h30.
        assertThat(direct.days().get(0).slots()).hasSize(22);
        assertThat(direct.days().get(0).slots().get(0).tableId()).isEqualTo(table.getId());
    }
}
