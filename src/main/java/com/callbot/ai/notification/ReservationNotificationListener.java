package com.callbot.ai.notification;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.callbot.ai.model.Customer;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
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

    /**
     * The three rows every message needs. Loaded once, by {@link #contextFor}, because
     * a notification with a missing restaurant or reservation is not a message worth
     * half-sending — it is a bug to log and drop.
     */
    private record Context(Reservation reservation, Customer customer, Restaurant restaurant) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReservationCreated(ReservationCreatedEvent event) {
        contextFor(event.reservationId(), "created").ifPresent(context -> {
            // A reservation awaiting its guarantee is not confirmed: telling the diner it is
            // would promise a table that is only held for a few more minutes.
            if (GuaranteeStatus.AWAITING.equals(context.reservation().getGuaranteeStatus())) {
                discord.sendClientSmsMessage(
                        GuaranteeMode.NO_SHOW.code().equals(context.reservation().getGuaranteeMode())
                                ? templates.forClientAwaitingNoShowGuarantee(
                                        context.reservation(), context.customer(), context.restaurant())
                                : templates.forClientAwaitingBookingFee(
                                        context.reservation(), context.customer(), context.restaurant()));
            } else {
                discord.sendClientSmsMessage(templates.forClient(
                        context.reservation(), context.customer(), context.restaurant()));
            }
            discord.sendReservationMessage(templates.forRestaurant(
                    context.reservation(), context.customer(), context.restaurant()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onGuaranteeExpired(ReservationGuaranteeExpiredEvent event) {
        contextFor(event.reservationId(), "guarantee expiry").ifPresent(context -> {
            discord.sendClientSmsMessage(templates.forClientGuaranteeExpired(
                    context.reservation(), context.customer(), context.restaurant()));
            discord.sendReservationMessage(templates.forRestaurantGuaranteeExpired(
                    context.reservation(), context.customer(), context.restaurant()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReservationConfirmed(ReservationConfirmedEvent event) {
        contextFor(event.reservationId(), "payment").ifPresent(context -> {
            discord.sendClientSmsMessage(templates.forClientConfirmedAfterPayment(
                    context.reservation(), context.customer(), context.restaurant()));
            discord.sendReservationMessage(templates.forRestaurantConfirmedAfterPayment(
                    context.reservation(), context.customer(), context.restaurant()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onCancelledByGuest(ReservationCancelledByGuestEvent event) {
        contextFor(event.reservationId(), "guest cancellation").ifPresent(context -> {
            discord.sendClientSmsMessage(event.refunded()
                    ? templates.forClientRefundIssued(
                            context.reservation(), context.customer(), context.restaurant())
                    : templates.forClientCancellationConfirmed(
                            context.reservation(), context.customer(), context.restaurant()));
            discord.sendReservationMessage(templates.forRestaurantCancelledByGuest(
                    context.reservation(), context.customer(), context.restaurant(), event.refunded()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReservationUpdated(ReservationUpdatedEvent event) {
        contextFor(event.reservationId(), "updated").ifPresent(context -> {
            discord.sendClientSmsMessage(templates.forClientUpdated(
                    context.reservation(), context.customer(), context.restaurant()));
            discord.sendReservationMessage(templates.forRestaurantUpdated(
                    context.reservation(), context.customer(), context.restaurant()));
        });
    }

    private Optional<Context> contextFor(UUID reservationId, String what) {
        Reservation reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            log.warn("Reservation {} not found when handling {} event", reservationId, what);
            return Optional.empty();
        }
        Restaurant restaurant = restaurantRepository.findById(reservation.getRestaurantId()).orElse(null);
        if (restaurant == null) {
            log.warn("Restaurant {} not found for reservation {}",
                    reservation.getRestaurantId(), reservation.getId());
            return Optional.empty();
        }
        Customer customer = reservation.getCustomerId() != null
                ? customerRepository.findById(reservation.getCustomerId()).orElse(null)
                : null;
        return Optional.of(new Context(reservation, customer, restaurant));
    }
}
