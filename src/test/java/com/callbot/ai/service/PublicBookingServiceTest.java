package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.callbot.ai.dto.PublicRescheduleRequest;
import com.callbot.ai.dto.PublicReservationRequest;
import com.callbot.ai.notification.ReservationUpdatedEvent;
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
        when(reservationService.slotsFor(eq(restaurant), eq(startsAt.toLocalDate()), eq(1), eq(2),
                eq(BookingPolicy.DEFAULT_DURATION), eq(null))).thenReturn(slotsWith(startsAt));
        when(customerRepository.findByRestaurantIdAndPhone(restaurantId, "+33612345678")).thenReturn(Optional.empty());
        when(customerRepository.save(any())).thenAnswer(i -> {
            Customer c = i.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(reservationRepository.saveAndFlush(any())).thenAnswer(i -> {
            Reservation r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        PublicReservationResponse response = service.create(restaurantId, request(startsAt, 2));

        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).saveAndFlush(saved.capture());
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
    void create_reusesTheCustomerFoundByNormalizedPhone_withoutRewritingIt() {
        OffsetDateTime startsAt = tomorrowEvening();
        Customer existing = Customer.builder().id(UUID.randomUUID()).restaurantId(restaurantId)
                .phone("+33612345678").firstName("N.").build();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), any(), anyInt(), eq(2), any(), any())).thenReturn(slotsWith(startsAt));
        when(customerRepository.findByRestaurantIdAndPhone(restaurantId, "+33612345678")).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reservationRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.create(restaurantId, request(startsAt, 2));

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(existing.getId());
        // Route anonyme : connaitre un numero ne permet pas de renommer son proprietaire.
        assertThat(saved.getValue().getFirstName()).isEqualTo("N.");
    }

    @Test
    void create_offTheProposedSlots_isRejectedWith409NoTable() {
        OffsetDateTime startsAt = tomorrowEvening();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), any(), anyInt(), eq(2), any(), any()))
                .thenReturn(slotsWith(startsAt.plusHours(1)));

        assertThatThrownBy(() -> service.create(restaurantId, request(startsAt, 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> {
                    assertThat(((BookingException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((BookingException) e).getCode()).isEqualTo("no_table");
                });
        verify(reservationRepository, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void create_inThePast_isRejectedWith400() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> service.create(restaurantId, request(OffsetDateTime.now().minusDays(1), 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("slot_out_of_window"));
        verify(reservationService, never()).slotsFor(any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void create_beyondSevenDays_isRejectedWith400() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> service.create(restaurantId, request(tomorrowEvening().plusDays(8), 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("slot_out_of_window"));
    }

    @Test
    void create_onTheSeventhDay_isAccepted_andOnTheEighth_isRejected() {
        ZoneId zone = ZoneId.of("Europe/Paris");
        OffsetDateTime lastDay = LocalDate.now(zone).plusDays(6).atTime(19, 30).atZone(zone).toOffsetDateTime();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), eq(lastDay.toLocalDate()), eq(1), eq(2), any(), any()))
                .thenReturn(slotsWith(lastDay));
        when(customerRepository.findByRestaurantIdAndPhone(any(), any())).thenReturn(Optional.empty());
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reservationRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.create(restaurantId, request(lastDay, 2));
        verify(reservationRepository).saveAndFlush(any());

        assertThatThrownBy(() -> service.create(restaurantId, request(lastDay.plusDays(1), 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("slot_out_of_window"));
    }

    @Test
    void create_whenThePhoneAlreadyHasAReservationThatDay_isRejectedWith409() {
        OffsetDateTime startsAt = tomorrowEvening();
        Customer existing = Customer.builder().id(UUID.randomUUID()).restaurantId(restaurantId)
                .phone("+33612345678").firstName("Nadia").build();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), any(), anyInt(), eq(2), any(), any())).thenReturn(slotsWith(startsAt));
        when(customerRepository.findByRestaurantIdAndPhone(restaurantId, "+33612345678")).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reservationRepository.countActiveByCustomerBetween(eq(restaurantId), eq(existing.getId()), any(), any()))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.create(restaurantId, request(startsAt, 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("already_booked"));
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_whenTheDatabaseRefusesTheOverlap_answersNoTable() {
        OffsetDateTime startsAt = tomorrowEvening();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), any(), anyInt(), eq(2), any(), any())).thenReturn(slotsWith(startsAt));
        when(customerRepository.findByRestaurantIdAndPhone(any(), any())).thenReturn(Optional.empty());
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reservationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("no_overlapping_reservation"));

        assertThatThrownBy(() -> service.create(restaurantId, request(startsAt, 2)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("no_table"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void slots_outsideTheSevenDayWindow_isRejectedWith400() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> service.slots(restaurantId, LocalDate.now().plusDays(10), 2))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("date_out_of_window"));
        assertThatThrownBy(() -> service.slots(restaurantId, LocalDate.now().minusDays(1), 2))
                .isInstanceOf(BookingException.class);
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
        LocalDate from = LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(2);
        RescheduleSlotsResponse expected = new RescheduleSlotsResponse(List.of());
        when(reservationService.slotsFor(restaurant, from, 5, 4, BookingPolicy.DEFAULT_DURATION, null)).thenReturn(expected);

        assertThat(service.slots(restaurantId, from, 4)).isSameAs(expected);
    }

    @Test
    void slots_fromTheLastBookableDay_neverGoBeyondTheWindow() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        LocalDate lastDay = LocalDate.now(ZoneId.of("Europe/Paris")).plusDays(BookingPolicy.WINDOW_DAYS - 1);
        RescheduleSlotsResponse expected = new RescheduleSlotsResponse(List.of());
        when(reservationService.slotsFor(restaurant, lastDay, 1, 2, BookingPolicy.DEFAULT_DURATION, null)).thenReturn(expected);

        assertThat(service.slots(restaurantId, lastDay, 2)).isSameAs(expected);
    }

    // --- replanification depuis le lien du message

    private Reservation existingReservation(String status) {
        OffsetDateTime startsAt = tomorrowEvening();
        return Reservation.builder().id(UUID.randomUUID()).restaurantId(restaurantId).customerId(UUID.randomUUID())
                .tableId(tableId).startsAt(startsAt).endsAt(startsAt.plusMinutes(90)).partySize(2)
                .status(status).source("web").build();
    }

    @Test
    void reschedule_onProposedSlot_movesTheReservationAndNotifies() {
        Reservation existing = existingReservation("pending");
        OffsetDateTime newStart = existing.getStartsAt().plusHours(1);
        when(reservationRepository.findByPublicToken(existing.getPublicToken())).thenReturn(Optional.of(existing));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(eq(restaurant), eq(newStart.toLocalDate()), eq(1), eq(3),
                eq(Duration.ofMinutes(90)), eq(existing.getId()))).thenReturn(slotsWith(newStart));
        when(reservationRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(customerRepository.findById(existing.getCustomerId())).thenReturn(Optional.empty());

        PublicReservationResponse response = service.reschedule(existing.getPublicToken(),
                new PublicRescheduleRequest(newStart, 3, "Poussette"));

        assertThat(existing.getStartsAt()).isEqualTo(newStart);
        assertThat(existing.getEndsAt()).isEqualTo(newStart.plusMinutes(90));
        assertThat(existing.getPartySize()).isEqualTo(3);
        assertThat(existing.getNotes()).isEqualTo("Poussette");
        assertThat(response.partySize()).isEqualTo(3);
        verify(events).publishEvent(any(ReservationUpdatedEvent.class));
    }

    @Test
    void reschedule_ofAPastReservation_isRefused() {
        Reservation past = existingReservation("pending");
        past.setStartsAt(OffsetDateTime.now().minusDays(3));
        past.setEndsAt(past.getStartsAt().plusMinutes(90));
        when(reservationRepository.findByPublicToken(past.getPublicToken())).thenReturn(Optional.of(past));

        assertThatThrownBy(() -> service.reschedule(past.getPublicToken(),
                new PublicRescheduleRequest(tomorrowEvening(), 2, null)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("not_reschedulable"));
    }

    @Test
    void reschedule_ofACancelledReservation_isRefused() {
        Reservation cancelled = existingReservation("cancelled");
        when(reservationRepository.findByPublicToken(cancelled.getPublicToken())).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.reschedule(cancelled.getPublicToken(),
                new PublicRescheduleRequest(tomorrowEvening(), 2, null)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("not_reschedulable"));
        assertThatThrownBy(() -> service.rescheduleSlots(cancelled.getPublicToken(), null))
                .isInstanceOf(BookingException.class);
    }

    @Test
    void reschedule_offTheProposedSlots_is409_andLeavesTheReservationUntouched() {
        Reservation existing = existingReservation("confirmed");
        OffsetDateTime original = existing.getStartsAt();
        when(reservationRepository.findByPublicToken(existing.getPublicToken())).thenReturn(Optional.of(existing));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));
        when(reservationService.slotsFor(any(), any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(slotsWith(original.plusHours(2)));

        assertThatThrownBy(() -> service.reschedule(existing.getPublicToken(),
                new PublicRescheduleRequest(original.plusHours(1), 2, null)))
                .isInstanceOf(BookingException.class)
                .satisfies(e -> assertThat(((BookingException) e).getCode()).isEqualTo("no_table"));
        assertThat(existing.getStartsAt()).isEqualTo(original);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void rescheduleSlots_excludeTheReservationItself_withItsOwnDuration() {
        Reservation existing = existingReservation("pending");
        when(reservationRepository.findByPublicToken(existing.getPublicToken())).thenReturn(Optional.of(existing));
        RescheduleSlotsResponse expected = new RescheduleSlotsResponse(List.of());
        when(reservationService.rescheduleSlots(existing.getId(), null, 4, null)).thenReturn(expected);

        assertThat(service.rescheduleSlots(existing.getPublicToken(), 4)).isSameAs(expected);
    }
}
