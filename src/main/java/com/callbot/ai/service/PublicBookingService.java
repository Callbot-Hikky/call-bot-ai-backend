package com.callbot.ai.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.callbot.ai.util.PhoneNumbers;
import com.callbot.ai.dto.PublicRescheduleRequest;
import com.callbot.ai.notification.ReservationUpdatedEvent;
import java.time.Duration;
import java.util.List;

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
    private final GuaranteePolicy guaranteePolicy;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public RescheduleSlotsResponse slots(UUID restaurantId, LocalDate fromDate, int partySize) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        requirePartySize(partySize);
        ZoneId zone = ZoneId.of(restaurant.getTimezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate from = fromDate != null ? fromDate : today;
        // On ne propose que ce que l'on accepte : la meme fenetre de 7 jours qu'a la creation.
        if (from.isBefore(today) || from.isAfter(lastBookableDay(today))) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "date_out_of_window",
                    "fromDate must be within the next " + BookingPolicy.WINDOW_DAYS + " days");
        }
        // Depuis J+3, il ne reste que 4 jours a proposer : jamais un creneau que create() refuserait.
        int dayCount = (int) (ChronoUnit.DAYS.between(from, lastBookableDay(today)) + 1);
        return reservationService.slotsFor(restaurant, from, dayCount, partySize, BookingPolicy.DEFAULT_DURATION, null);
    }

    public PublicReservationResponse create(UUID restaurantId, PublicReservationRequest request) {
        Restaurant restaurant = requireRestaurant(restaurantId);
        requirePartySize(request.partySize());
        reservationRepository.lockRestaurant(restaurantId);
        ZoneId zone = ZoneId.of(restaurant.getTimezone());
        OffsetDateTime now = OffsetDateTime.now(zone);
        LocalDate day = request.startsAt().atZoneSameInstant(zone).toLocalDate();
        LocalDate today = now.toLocalDate();
        if (request.startsAt().isBefore(now) || day.isAfter(lastBookableDay(today))) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "slot_out_of_window",
                    "The slot must be in the next " + BookingPolicy.WINDOW_DAYS + " days");
        }

        // Le creneau demande doit etre un de ceux que l'on propose, a l'instant de la creation :
        // il porte la table et l'heure de fin. Un creneau pris entre l'affichage et le clic
        // n'est plus propose, donc refuse ici (409), avant meme la contrainte de la base.
        // Seul le jour demande est recalcule : pas les sept.
        RescheduleSlotsResponse.Slot slot = reservationService
                .slotsFor(restaurant, day, 1, request.partySize(), BookingPolicy.DEFAULT_DURATION, null)
                .days().stream()
                .flatMap(d -> d.slots().stream())
                .filter(s -> s.startsAt().isEqual(request.startsAt()))
                .findFirst()
                .orElseThrow(() -> new BookingException(HttpStatus.CONFLICT, "no_table",
                        "No table is available for that slot"));

        Customer customer = upsertCustomer(restaurantId, request.customer());
        requireNoOtherReservationThatDay(restaurant, customer, day, zone);
        Reservation pending = Reservation.builder()
                .restaurantId(restaurantId)
                .customerId(customer.getId())
                .tableId(slot.tableId())
                .tableIds(new LinkedHashSet<>(Set.of(slot.tableId())))
                .startsAt(slot.startsAt())
                .endsAt(slot.endsAt())
                .partySize(request.partySize())
                .status("pending")
                .source("web")
                .notes(blankToNull(request.notes()))
                .build();
        // Meme garantie que par telephone : reserver depuis le QR ne doit pas permettre
        // d'echapper aux frais que le restaurant demande a tous ses clients. La politique
        // pose aussi les jetons d'annulation et de modification, que le parcours web
        // n'avait pas du tout. Appelee AVANT la sauvegarde, comme les deux autres portes
        // d'entree : le listener de notification relit l'etat commite pour choisir son
        // message. Aucun membre du personnel n'est present ici, donc aucune exemption.
        guaranteePolicy.applyOnCreation(pending, restaurant, null);
        Reservation reservation;
        try {
            reservation = reservationRepository.saveAndFlush(pending);
        } catch (DataIntegrityViolationException e) {
            // Deux clients sur le dernier creneau au meme instant : la base tranche, le second
            // recoit le meme message que si le creneau n'etait plus propose.
            throw new BookingException(HttpStatus.CONFLICT, "no_table", "No table is available for that slot");
        }
        events.publishEvent(new ReservationCreatedEvent(reservation.getId()));
        return toResponse(reservation, restaurant, customer);
    }

    /** Creneaux pour deplacer sa reservation : la duree d'origine, la reservation exclue des tables occupees. */
    @Transactional(readOnly = true)
    public RescheduleSlotsResponse rescheduleSlots(UUID token, Integer partySize) {
        Reservation reservation = requireReschedulable(token);
        int size = partySize != null ? partySize : reservation.getPartySize();
        requirePartySize(size);
        return reservationService.rescheduleSlots(reservation.getId(), null, size, null);
    }

    /**
     * Deplace la reservation depuis le lien du message de confirmation. Comme a la creation,
     * seul un creneau propose est accepte : table et heure de fin viennent de lui. Le nom du
     * client n'est pas modifiable ici, la route est anonyme.
     */
    public PublicReservationResponse reschedule(UUID token, PublicRescheduleRequest request) {
        Reservation reservation = requireReschedulable(token);
        Restaurant restaurant = requireRestaurant(reservation.getRestaurantId());
        requirePartySize(request.partySize());
        reservationRepository.lockRestaurant(reservation.getRestaurantId());
        ZoneId zone = ZoneId.of(restaurant.getTimezone());
        OffsetDateTime now = OffsetDateTime.now(zone);
        LocalDate day = request.startsAt().atZoneSameInstant(zone).toLocalDate();
        LocalDate today = now.toLocalDate();
        if (request.startsAt().isBefore(now) || day.isAfter(lastBookableDay(today))) {
            throw new BookingException(HttpStatus.BAD_REQUEST, "slot_out_of_window",
                    "The slot must be in the next " + BookingPolicy.WINDOW_DAYS + " days");
        }
        Duration duration = reservation.getEndsAt() != null
                ? Duration.between(reservation.getStartsAt(), reservation.getEndsAt())
                : BookingPolicy.DEFAULT_DURATION;
        Customer current = reservation.getCustomerId() == null ? null
                : customerRepository.findById(reservation.getCustomerId()).orElse(null);
        if (current != null) {
            requireNoOtherReservationThatDay(restaurant, current, day, zone, reservation);
        }
        RescheduleSlotsResponse.Slot slot = reservationService
                .slotsFor(restaurant, day, 1, request.partySize(), duration, reservation.getId())
                .days().stream()
                .flatMap(d -> d.slots().stream())
                .filter(s -> s.startsAt().isEqual(request.startsAt()))
                .findFirst()
                .orElseThrow(() -> new BookingException(HttpStatus.CONFLICT, "no_table",
                        "No table is available for that slot"));
        reservation.setStartsAt(slot.startsAt());
        reservation.setEndsAt(slot.endsAt());
        reservation.setTableId(slot.tableId());
        // Collection modifiable : Hibernate la gere, un Set immuable leverait UnsupportedOperationException.
        reservation.setTableIds(new LinkedHashSet<>(Set.of(slot.tableId())));
        reservation.setPartySize(request.partySize());
        if (request.notes() != null) {
            reservation.setNotes(blankToNull(request.notes()));
        }
        Reservation saved;
        try {
            saved = reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            throw new BookingException(HttpStatus.CONFLICT, "no_table", "No table is available for that slot");
        }
        events.publishEvent(new ReservationUpdatedEvent(saved.getId()));
        Customer customer = saved.getCustomerId() == null ? null
                : customerRepository.findById(saved.getCustomerId()).orElse(null);
        return toResponse(saved, restaurant, customer);
    }

    /** Une reservation annulee ou deja passee ne se deplace plus. */
    private Reservation requireReschedulable(UUID token) {
        Reservation reservation = findByToken(token);
        boolean past = reservation.getStartsAt() != null && reservation.getStartsAt().isBefore(OffsetDateTime.now());
        if (past || !List.of("pending", "confirmed").contains(reservation.getStatus())) {
            throw new BookingException(HttpStatus.CONFLICT, "not_reschedulable",
                    "This reservation can no longer be moved");
        }
        return reservation;
    }

    @Transactional(readOnly = true)
    public PublicReservationResponse get(UUID token) {
        Reservation reservation = findByToken(token);
        Restaurant restaurant = requireRestaurant(reservation.getRestaurantId());
        Customer customer = reservation.getCustomerId() == null ? null
                : customerRepository.findById(reservation.getCustomerId()).orElse(null);
        return toResponse(reservation, restaurant, customer);
    }

    /**
     * Un habitue garde sa fiche : le telephone normalise identifie le client dans ce restaurant.
     * Une fiche existante n'est jamais reecrite depuis une route anonyme : connaitre le numero
     * de quelqu'un ne donne pas le droit de changer son prenom. Le prenom saisi ne sert qu'a
     * une fiche neuve ou vide.
     */
    private Customer upsertCustomer(UUID restaurantId, PublicReservationRequest.Customer input) {
        String phone = PhoneNumbers.normalize(input.phone());
        Customer customer = customerRepository.findByRestaurantIdAndPhone(restaurantId, phone)
                .orElseGet(() -> Customer.builder().restaurantId(restaurantId).phone(phone).build());
        if (customer.getFirstName() == null || customer.getFirstName().isBlank()) {
            customer.setFirstName(input.firstName().trim());
        }
        return customerRepository.save(customer);
    }

    /** Un meme numero ne remplit pas le carnet : une reservation active par jour et par restaurant. */
    private void requireNoOtherReservationThatDay(Restaurant restaurant, Customer customer, LocalDate day, ZoneId zone) {
        requireNoOtherReservationThatDay(restaurant, customer, day, zone, null);
    }

    /** En replanification, la reservation deplacee ne compte pas contre elle-meme. */
    private void requireNoOtherReservationThatDay(Restaurant restaurant, Customer customer, LocalDate day, ZoneId zone,
            Reservation self) {
        if (customer.getId() == null) {
            return;
        }
        OffsetDateTime from = day.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        long active = reservationRepository.countActiveByCustomerBetween(restaurant.getId(), customer.getId(), from, to);
        if (self != null && self.getStartsAt() != null
                && !self.getStartsAt().isBefore(from) && self.getStartsAt().isBefore(to)) {
            active--;
        }
        if (active >= BookingPolicy.MAX_ACTIVE_PER_DAY) {
            throw new BookingException(HttpStatus.CONFLICT, "already_booked",
                    "This phone number already has a reservation that day");
        }
    }

    private static LocalDate lastBookableDay(LocalDate today) {
        return BookingPolicy.lastBookableDay(today);
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

    /** Le public ne connait la reservation que par son jeton : jamais par son identifiant interne. */
    private Reservation findByToken(UUID token) {
        return reservationRepository.findByPublicToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", token));
    }

    /** Le champ « id » de la vue publique est le jeton : c'est lui que le client garde dans ses liens. */
    private static PublicReservationResponse toResponse(Reservation r, Restaurant restaurant, Customer customer) {
        return new PublicReservationResponse(r.getPublicToken(), restaurant.getId(), restaurant.getName(),
                r.getStartsAt(), r.getEndsAt(), r.getPartySize(), r.getStatus(),
                displayName(customer));
    }

    /** Prenom, sinon nom : une fiche creee par l'assistant vocal peut n'avoir que le nom. */
    private static String displayName(Customer customer) {
        if (customer == null) {
            return null;
        }
        String first = customer.getFirstName();
        return first != null && !first.isBlank() ? first : customer.getLastName();
    }
}
