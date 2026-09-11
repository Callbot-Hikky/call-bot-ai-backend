package com.callbot.ai.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.PublicReservationRequest;
import com.callbot.ai.dto.PublicReservationResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.exception.BookingException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationCreatedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reservation en ligne, sans compte : un client arrive par le lien ou le QR du
 * restaurateur, voit les creneaux libres et reserve. Le calcul des creneaux est celui de
 * la replanification ({@link ReservationService#slotsFor}) ; la creation ne fait
 * confiance qu'a ce calcul pour la table et l'heure de fin.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PublicBookingService {

    private final ReservationService reservationService;
    private final RestaurantRepository restaurantRepository;
    private final ReservationRepository reservationRepository;
    private final CustomerRepository customerRepository;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public RescheduleSlotsResponse slots(UUID restaurantId, LocalDate fromDate, int partySize) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        requirePartySize(partySize);
        ZoneId zone = ZoneId.of(restaurant.getTimezone());
        LocalDate from = fromDate != null ? fromDate : LocalDate.now(zone);
        return reservationService.slotsFor(restaurant, from, partySize, BookingPolicy.DEFAULT_DURATION, null);
    }

    public PublicReservationResponse create(UUID restaurantId, PublicReservationRequest request) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        requirePartySize(request.partySize());
        ZoneId zone = ZoneId.of(restaurant.getTimezone());
        OffsetDateTime now = OffsetDateTime.now(zone);
        LocalDate day = request.startsAt().atZoneSameInstant(zone).toLocalDate();
        LocalDate today = now.toLocalDate();
        if (request.startsAt().isBefore(now) || day.isAfter(today.plusDays(BookingPolicy.WINDOW_DAYS - 1))) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "slot_out_of_window",
                    "The slot must be in the next " + BookingPolicy.WINDOW_DAYS + " days");
        }

        // Le creneau demande doit etre un de ceux que l'on propose, a l'instant de la creation :
        // il porte la table et l'heure de fin. Un creneau pris entre l'affichage et le clic
        // n'est plus propose, donc refuse ici (409), avant meme la contrainte de la base.
        RescheduleSlotsResponse.Slot slot = reservationService
                .slotsFor(restaurant, day, request.partySize(), BookingPolicy.DEFAULT_DURATION, null)
                .days().stream()
                .flatMap(d -> d.slots().stream())
                .filter(s -> s.startsAt().isEqual(request.startsAt()))
                .findFirst()
                .orElseThrow(() -> new BookingException(HttpStatus.CONFLICT, "no_table",
                        "No table is available for that slot"));

        Customer customer = upsertCustomer(restaurantId, request.customer());
        Reservation reservation = reservationRepository.save(Reservation.builder()
                .restaurantId(restaurantId)
                .customerId(customer.getId())
                .tableId(slot.tableId())
                .tableIds(Set.of(slot.tableId()))
                .startsAt(slot.startsAt())
                .endsAt(slot.endsAt())
                .partySize(request.partySize())
                .status("pending")
                .source("web")
                .notes(blankToNull(request.notes()))
                .build());
        events.publishEvent(new ReservationCreatedEvent(reservation.getId()));
        return toResponse(reservation, restaurant, customer);
    }

    @Transactional(readOnly = true)
    public PublicReservationResponse get(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));
        Restaurant restaurant = requireRestaurant(reservation.getRestaurantId());
        Customer customer = reservation.getCustomerId() == null ? null
                : customerRepository.findById(reservation.getCustomerId()).orElse(null);
        return toResponse(reservation, restaurant, customer);
    }

    /** Un habitue garde sa fiche : le telephone normalise identifie le client dans ce restaurant. */
    private Customer upsertCustomer(UUID restaurantId, PublicReservationRequest.Customer input) {
        String phone = normalizePhone(input.phone());
        Customer customer = customerRepository.findByRestaurantIdAndPhone(restaurantId, phone)
                .orElseGet(() -> Customer.builder().restaurantId(restaurantId).phone(phone).build());
        customer.setFirstName(input.firstName().trim());
        return customerRepository.save(customer);
    }

    /** « 06 12 34 56 78 », « 06.12.34.56.78 » et « 0612345678 » designent le meme client. */
    static String normalizePhone(String raw) {
        return raw.replaceAll("[\\s.()-]", "");
    }

    private void requirePartySize(int partySize) {
        if (partySize < 1 || partySize > BookingPolicy.MAX_PARTY_SIZE) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "invalid_party_size",
                    "partySize must be between 1 and " + BookingPolicy.MAX_PARTY_SIZE);
        }
    }

    private Restaurant requireRestaurant(UUID restaurantId) {
        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static PublicReservationResponse toResponse(Reservation r, Restaurant restaurant, Customer customer) {
        return new PublicReservationResponse(r.getId(), restaurant.getId(), restaurant.getName(),
                r.getStartsAt(), r.getEndsAt(), r.getPartySize(), r.getStatus(),
                Optional.ofNullable(customer).map(Customer::getFirstName).orElse(null));
    }
}
