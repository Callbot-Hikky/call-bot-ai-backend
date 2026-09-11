package com.callbot.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class RestaurantAccessTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private Authentication authentication;
    @InjectMocks
    private RestaurantAccess access;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID restaurantId = UUID.randomUUID();

    private void authenticatedAs(UUID orgId) {
        when(authentication.getName()).thenReturn("owner@example.com");
        when(userRepository.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(User.builder().email("owner@example.com").organizationId(orgId).build()));
    }

    @Test
    void requireOwned_whenSameOrganization_returnsRestaurant() {
        authenticatedAs(organizationId);
        Restaurant restaurant = Restaurant.builder().id(restaurantId).organizationId(organizationId).name("Chez Test").build();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        assertThat(access.requireOwned(restaurantId, authentication)).isSameAs(restaurant);
    }

    @Test
    void requireOwned_whenOtherOrganization_throwsAccessDenied() {
        authenticatedAs(organizationId);
        Restaurant other = Restaurant.builder().id(restaurantId).organizationId(UUID.randomUUID()).name("Autre").build();
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> access.requireOwned(restaurantId, authentication))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireOwned_whenRestaurantMissing_throwsNotFound() {
        authenticatedAs(organizationId);
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireOwned(restaurantId, authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requireOwned_whenUserUnknown_throwsNotFound() {
        when(authentication.getName()).thenReturn("ghost@example.com");
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireOwned(restaurantId, authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
