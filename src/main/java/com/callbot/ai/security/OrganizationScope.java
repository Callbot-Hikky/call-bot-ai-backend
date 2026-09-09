package com.callbot.ai.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * Answers the one question every operational endpoint has to ask: does the caller's
 * organization own this?
 *
 * <p>Written once and shared rather than repeated in each service, because it is the
 * same three lines everywhere and getting one of them wrong is a data leak.
 *
 * <p>A caller with no organization is the AI microservice, authenticated by API key. It
 * legitimately acts for every restaurant, so it is left unrestricted — hence
 * {@link Optional} rather than a plain id.
 *
 * <p>Ownership failures raise <em>not found</em>, never <em>forbidden</em>: a 403 would
 * confirm that the resource exists, which is itself a leak — it tells a stranger that a
 * given restaurant, diner or table is real.
 */
@Component
@RequiredArgsConstructor
public class OrganizationScope {

    private final CallerOrganizationResolver callerOrganization;
    private final RestaurantRepository restaurantRepository;

    /** Empty when the caller is the AI microservice, which no scope applies to. */
    public Optional<UUID> organizationOf(String callerEmail) {
        return callerOrganization.resolve(callerEmail);
    }

    public Optional<UUID> userIdOf(String callerEmail) {
        return callerOrganization.resolveUserId(callerEmail);
    }

    /** @throws ResourceNotFoundException if the restaurant is absent or someone else's */
    public Restaurant ownedRestaurant(UUID restaurantId, String callerEmail) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        requireOwnership(restaurant, restaurantId, callerEmail);
        return restaurant;
    }

    public void requireOwnedRestaurant(UUID restaurantId, String callerEmail) {
        ownedRestaurant(restaurantId, callerEmail);
    }

    /**
     * Checks a resource that lives under a restaurant — a diner, a table, an opening
     * hour. {@code resource} names what the caller asked for, so the error never reveals
     * that the restaurant behind it exists.
     */
    public void requireOwnedThrough(UUID restaurantId, String resource, UUID resourceId,
            String callerEmail) {
        Optional<UUID> caller = organizationOf(callerEmail);
        if (caller.isEmpty()) {
            return;
        }
        UUID owner = restaurantRepository.findById(restaurantId)
                .map(Restaurant::getOrganizationId)
                .orElse(null);
        if (!caller.get().equals(owner)) {
            throw new ResourceNotFoundException(resource, resourceId);
        }
    }

    /**
     * The restaurants a caller may see. Empty result and "no restriction" are different
     * things, so the microservice case is signalled by an empty {@link Optional}.
     */
    public Optional<List<UUID>> ownedRestaurantIds(String callerEmail) {
        return organizationOf(callerEmail)
                .map(organizationId -> restaurantRepository.findByOrganizationId(organizationId)
                        .stream()
                        .map(Restaurant::getId)
                        .toList());
    }

    private void requireOwnership(Restaurant restaurant, UUID askedFor, String callerEmail) {
        Optional<UUID> caller = organizationOf(callerEmail);
        if (caller.isPresent() && !caller.get().equals(restaurant.getOrganizationId())) {
            throw new ResourceNotFoundException("Restaurant", askedFor);
        }
    }
}
