package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.ConnectAccountResponse;
import com.callbot.ai.dto.ConnectOnboardingResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.UserRepository;
import com.callbot.ai.security.OrganizationScope;

import lombok.RequiredArgsConstructor;

/**
 * A restaurant's payment account: opening it, and knowing what it may do.
 *
 * <p>One account per restaurant, not per owner. A Stripe account is tied to a legal
 * entity and a bank account, and two establishments of the same owner are often two
 * companies banking separately — one shared account would pay the wrong one.
 *
 * <p>{@link #requireAbleToCharge} is the gate every paying flow passes.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ConnectAccountService {

    private static final Logger log = LoggerFactory.getLogger(ConnectAccountService.class);

    private final RestaurantRepository restaurantRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final OrganizationScope scope;
    private final StripeConnectGateway connect;

    @Transactional(readOnly = true)
    public ConnectAccountResponse status(UUID restaurantId, String callerEmail) {
        return describe(scope.ownedRestaurant(restaurantId, callerEmail));
    }

    /**
     * Opens the account on first call, then hands back a fresh onboarding link.
     *
     * <p>Stripe's links expire within minutes, so this is called again every time the
     * restaurateur clicks — never cached.
     */
    public ConnectOnboardingResponse startOnboarding(UUID restaurantId, String callerEmail) {
        Restaurant restaurant = scope.ownedRestaurant(restaurantId, callerEmail);
        requireSignedIn(callerEmail);

        if (restaurant.getStripeAccountId() == null) {
            String email = userRepository.findByEmail(callerEmail).map(User::getEmail).orElse(null);
            restaurant.setStripeAccountId(
                    connect.createConnectedAccount(email, restaurant.getName()));
            restaurantRepository.save(restaurant);
        }

        return new ConnectOnboardingResponse(connect.createOnboardingLink(restaurant.getStripeAccountId()));
    }

    /**
     * Asks Stripe what the account may now do, so a restaurateur returning from
     * onboarding sees it unlocked without waiting on a webhook.
     */
    public ConnectAccountResponse refresh(UUID restaurantId, String callerEmail) {
        Restaurant restaurant = scope.ownedRestaurant(restaurantId, callerEmail);
        if (restaurant.getStripeAccountId() == null) {
            return describe(restaurant);
        }
        save(restaurant, connect.fetchStatus(restaurant.getStripeAccountId()));
        return describe(restaurant);
    }

    /** Records what Stripe now allows. Called from the webhook. */
    public void apply(ConnectAccountStatus status) {
        restaurantRepository.findByStripeAccountId(status.accountId())
                .ifPresentOrElse(
                        restaurant -> save(restaurant, status),
                        () -> log.warn("Stripe account {} belongs to no restaurant", status.accountId()));
    }

    /**
     * Refuses anything that would take a diner's money through an account Stripe has not
     * cleared: the diner would reach a payment page that fails, having already been told
     * their table is held.
     */
    public void requireAbleToCharge(UUID restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        if (!restaurant.isStripeChargesEnabled()) {
            throw new InvalidRequestException(
                    "Le compte de paiement de ce restaurant n'est pas encore validé par Stripe : "
                            + "terminez l'inscription avant d'activer un mode payant.");
        }
    }

    /** Records a bank dispute against the restaurant whose fee was contested. */
    public void recordDispute(Restaurant restaurant) {
        restaurant.setStripeDisputeCount(restaurant.getStripeDisputeCount() + 1);
        restaurant.setStripeLastDisputeAt(OffsetDateTime.now());
        restaurantRepository.save(restaurant);
    }

    private void save(Restaurant restaurant, ConnectAccountStatus status) {
        boolean wasBlocked = !restaurant.isStripeChargesEnabled();
        restaurant.setStripeChargesEnabled(status.chargesEnabled());
        restaurant.setStripePayoutsEnabled(status.payoutsEnabled());
        restaurant.setStripeDetailsSubmitted(status.detailsSubmitted());
        if (wasBlocked && status.chargesEnabled()) {
            restaurant.setStripeOnboardedAt(OffsetDateTime.now());
        }
        restaurantRepository.save(restaurant);
    }

    /** The dispute count is only meaningful next to how many fees were actually taken. */
    private ConnectAccountResponse describe(Restaurant restaurant) {
        return ConnectAccountResponse.from(restaurant,
                reservationRepository.countPaidFor(restaurant.getId()));
    }

    private void requireSignedIn(String callerEmail) {
        if (scope.organizationOf(callerEmail).isEmpty()) {
            throw new InvalidRequestException(
                    "Seul un utilisateur signé peut ouvrir un compte de paiement");
        }
    }
}
