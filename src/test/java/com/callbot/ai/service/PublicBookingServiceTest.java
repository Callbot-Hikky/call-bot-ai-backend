package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.springframework.http.HttpStatus;

import com.callbot.ai.dto.PublicReservationRequest;
import com.callbot.ai.dto.PublicReservationResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.exception.BookingException;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationCreatedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class PublicBookingServiceTest {

    @Mock
    private ReservationService reservationService;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private PublicBookingService service;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID tableId = UUID.randomUUID();
    private final Restaurant restaurant = Restaurant.builder().id(restaurantId).name("Chez Test")
            .phoneNumber("+33100000000").timezone("Europe/Paris").locale("fr").build();

    // Demain 19h30 a Paris : dans la fenetre, jamais dans le passe.
    private OffsetDateTime tomorrowEvening() {
        ZoneId zone = ZoneId.of("Europe/Paris");
        return LocalDate.now(zone).plusDays(1).atTime(19, 30).atZone(zone).toOffsetDateTime();
    }

    private RescheduleSlotsResponse slotsWith(OffsetDateTime startsAt) {
        RescheduleSlotsResponse.Slot slot = new RescheduleSlotsResponse.Slot(startsAt,
                startsAt.plus(Duration.ofMinutes(90)), tableId, 4);
        return new RescheduleSlotsResponse(List.of(
                new RescheduleSlotsResponse.Day(startsAt.toLocalDate(), List.of(slot))));
    }

    private PublicReservationRequest request(OffsetDateTime startsAt, int partySize) {
        return new PublicReservationRequest(startsAt, partySize,
                new PublicReservationRequest.Customer("Nadia", "06 12 34 56 78"), "  ");
    }

    @Test
    void create_onProposedSlot_savesWebReservationAndNotifies() {
        OffsetDateTime startsAt = tomorrowEvening();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(eq(restaurant), any(), eq(2), eq(BookingPolicy.DEFAULT_DURATION), eq(null)))
                .thenReturn(slotsWith(startsAt));
        when(customerRepository.findByRestaurantIdAndPhone(restaurantId, "0612345678")).thenReturn(Optional.empty());
        when(customerRepository.save(any())).thenAnswer(i -> {
            Customer c = i.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(reservationRepository.save(any())).thenAnswer(i -> {
            Reservation r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        PublicReservationResponse response = service.create(restaurantId, request(startsAt, 2));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo("web");
        assertThat(saved.getValue().getStatus()).isEqualTo("pending");
        assertThat(saved.getValue().getTableId()).isEqualTo(tableId);
        assertThat(saved.getValue().getTableIds()).containsExactly(tableId);
        assertThat(saved.getValue().getEndsAt()).isEqualTo(startsAt.plusMinutes(90));
        assertThat(saved.getValue().getNotes()).isNull();
        verify(events).publishEvent(any(ReservationCreatedEvent.class));
        assertThat(response.restaurantName()).isEqualTo("Chez Test");
        assertThat(response.customerFirstName()).isEqualTo("Nadia");
    }

    @Test
    void create_reusesTheCustomerFoundByNormalizedPhone() {
        OffsetDateTime startsAt = tomorrowEvening();
        Customer existing = Customer.builder().id(UUID.randomUUID()).restaurantId(restaurantId)
                .phone("0612345678").firstName("N.").build();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), any(), eq(2), any(), any())).thenReturn(slotsWith(startsAt));
        when(customerRepository.findByRestaurantIdAndPhone(restaurantId, "0612345678")).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.create(restaurantId, request(startsAt, 2));

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(existing.getId());
        assertThat(saved.getValue().getFirstName()).isEqualTo("Nadia");
    }

    @Test
    void create_offTheProposedSlots_isRejectedWith409NoTable() {
        OffsetDateTime startsAt = tomorrowEvening();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), any(), eq(2), any(), any()))
                .thenReturn(slotsWith(startsAt.plusHours(1)));

        assertThatThrownBy(() -> service.create(restaurantId, request(startsAt, 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> {
                    assertThat(((BookingException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((BookingException) e).getCode()).isEqualTo("no_table");
                });
        verify(reservationRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void create_inThePast_isRejectedWith400() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> service.create(restaurantId, request(OffsetDateTime.now().minusDays(1), 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("slot_out_of_window"));
        verify(reservationService, never()).slotsFor(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void create_beyondSevenDays_isRejectedWith400() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> service.create(restaurantId, request(tomorrowEvening().plusDays(8), 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("slot_out_of_window"));
    }

    @Test
    void partySize_outsideOneToFifteen_isRejected() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> service.slots(restaurantId, null, 16))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("invalid_party_size"));
        assertThatThrownBy(() -> service.slots(restaurantId, null, 0))
                .isInstanceOf(BookingException.class);
    }

    @Test
    void slots_delegateToTheSharedAlgorithmWithDefaultDurationAndNoExclusion() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        LocalDate from = LocalDate.of(2030, 1, 1);
        RescheduleSlotsResponse expected = new RescheduleSlotsResponse(List.of());
        when(reservationService.slotsFor(restaurant, from, 4, BookingPolicy.DEFAULT_DURATION, null)).thenReturn(expected);

        assertThat(service.slots(restaurantId, from, 4)).isSameAs(expected);
    }

    @Test
    void normalizePhone_dropsSpacesDotsAndDashes() {
        assertThat(PublicBookingService.normalizePhone("06 12.34-56 78")).isEqualTo("0612345678");
        assertThat(PublicBookingService.normalizePhone("+33 (0)6 12 34 56 78")).isEqualTo("+330612345678");
    }
}
