package com.callbot.ai.security;

import java.util.UUID;

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
 * Verifie qu'un restaurant appartient a l'organisation de l'utilisateur
 * authentifie. Le sujet du JWT est l'email (voir {@code JwtService} et
 * {@code AppUserDetailsService}), donc {@code authentication.getName()} suffit
 * a retrouver l'utilisateur, comme le fait {@code MeController}.
 */
@Component
@RequiredArgsConstructor
public class RestaurantAccess {

    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;

    @Transactional(readOnly = true)
    public Restaurant requireOwned(UUID restaurantId, Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", email));
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantId));
        if (!restaurant.getOrganizationId().equals(user.getOrganizationId())) {
            throw new AccessDeniedException("Restaurant " + restaurantId + " does not belong to your organization");
        }
        return restaurant;
    }
}
