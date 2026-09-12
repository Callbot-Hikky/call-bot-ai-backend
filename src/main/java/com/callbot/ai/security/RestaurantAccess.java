package com.callbot.ai.security;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * Propriete des donnees : un utilisateur ne voit et ne modifie que ce qui appartient a
 * son organisation. Le principal est l'email (voir {@code AppUserDetailsService}), donc
 * {@code authentication.getName()} suffit a retrouver l'utilisateur, comme dans MeController.
 * Chaque controleur admin passe par ici avant de toucher a un restaurant ou a ses donnees.
 */
@Component
@RequiredArgsConstructor
public class RestaurantAccess {

    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;

    /** L'organisation de l'utilisateur connecte. */
    @Transactional(readOnly = true)
    public UUID organizationOf(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", email));
        return user.getOrganizationId();
    }

    /** Refuse toute organisation autre que celle de l'utilisateur (creation de restaurant). */
    @Transactional(readOnly = true)
    public void requireOrganization(UUID organizationId, Authentication authentication) {
        if (organizationId == null || !organizationId.equals(organizationOf(authentication))) {
            throw new AccessDeniedException("This organization is not yours");
        }
    }

    /** Le restaurant existe et appartient a l'organisation de l'utilisateur. */
    @Transactional(readOnly = true)
    public Restaurant requireOwned(UUID restaurantId, Authentication authentication) {
        UUID organizationId = organizationOf(authentication);
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        if (!restaurant.getOrganizationId().equals(organizationId)) {
            throw new AccessDeniedException("Restaurant " + restaurantId + " does not belong to your organization");
        }
        return restaurant;
    }

    /** Les restaurants de l'utilisateur : pour borner une liste demandee sans restaurant precis. */
    @Transactional(readOnly = true)
    public Set<UUID> ownedRestaurantIds(Authentication authentication) {
        return restaurantRepository.findByOrganizationId(organizationOf(authentication)).stream()
                .map(Restaurant::getId)
                .collect(Collectors.toSet());
    }
}
