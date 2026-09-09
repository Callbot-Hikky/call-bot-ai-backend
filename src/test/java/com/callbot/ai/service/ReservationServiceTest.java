package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;
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
    private ApplicationEventPublisher events;
    @Mock
    private OrganizationScope scope;
    @Mock
    private GuaranteePolicy guaranteePolicy;
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
}
