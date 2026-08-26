package com.callbot.ai.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.service.DiscordNotificationService;
import com.callbot.ai.service.ReservationMessageTemplates;

@Component
public class ReservationNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationNotificationListener.class);

    private final ReservationRepository reservationRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
    private final ReservationMessageTemplates templates;
    private final DiscordNotificationService discord;

    public ReservationNotificationListener(ReservationRepository reservationRepository,
                                           CustomerRepository customerRepository,
                                           RestaurantRepository restaurantRepository,
                                           ReservationMessageTemplates templates,
                                           DiscordNotificationService discord) {
        this.reservationRepository = reservationRepository;
        this.customerRepository = customerRepository;
        this.restaurantRepository = restaurantRepository;
        this.templates = templates;
        this.discord = discord;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReservationCreated(ReservationCreatedEvent event) {
        Reservation reservation = reservationRepository.findById(event.reservationId()).orElse(null);
        if (reservation == null) {
            log.warn("Reservation {} not found when handling created event", event.reservationId());
            return;
        }
        Restaurant restaurant = restaurantRepository.findById(reservation.getRestaurantId()).orElse(null);
        if (restaurant == null) {
            log.warn("Restaurant {} not found for reservation {}", reservation.getRestaurantId(), reservation.getId());
            return;
        }
        Customer customer = reservation.getCustomerId() != null
                ? customerRepository.findById(reservation.getCustomerId()).orElse(null)
                : null;

        discord.sendClientSmsMessage(templates.forClient(reservation, customer, restaurant));
        discord.sendReservationMessage(templates.forRestaurant(reservation, customer, restaurant));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReservationUpdated(ReservationUpdatedEvent event) {
        Reservation reservation = reservationRepository.findById(event.reservationId()).orElse(null);
        if (reservation == null) {
            log.warn("Reservation {} not found when handling updated event", event.reservationId());
            return;
        }
        Restaurant restaurant = restaurantRepository.findById(reservation.getRestaurantId()).orElse(null);
        if (restaurant == null) {
            log.warn("Restaurant {} not found for reservation {}", reservation.getRestaurantId(), reservation.getId());
            return;
        }
        Customer customer = reservation.getCustomerId() != null
                ? customerRepository.findById(reservation.getCustomerId()).orElse(null)
                : null;

        discord.sendClientSmsMessage(templates.forClientUpdated(reservation, customer, restaurant));
        discord.sendReservationMessage(templates.forRestaurantUpdated(reservation, customer, restaurant));
    }
}
