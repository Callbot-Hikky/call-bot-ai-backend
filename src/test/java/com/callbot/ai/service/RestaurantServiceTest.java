package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.dto.RestaurantRequest;
import com.callbot.ai.dto.RestaurantResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.security.OrganizationScope;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private OrganizationScope scope;
    @InjectMocks
    private RestaurantService restaurantService;

    private static final String CALLER = "owner@resto.fr";

    private final UUID orgId = UUID.randomUUID();

    private RestaurantRequest request() {
        return new RestaurantRequest(orgId, "Chez Test", "+33100000001",
                null, null, null, null, null, null, null);
    }

    @Test
    void create_whenOrganizationExists_appliesDefaultsAndSaves() {
        when(organizationRepository.existsById(orgId)).thenReturn(true);
        when(scope.organizationOf(CALLER)).thenReturn(Optional.of(orgId));
        when(restaurantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        RestaurantResponse response = restaurantService.create(request(), CALLER);

        assertThat(response.name()).isEqualTo("Chez Test");
        assertThat(response.timezone()).isEqualTo("Europe/Paris");
        assertThat(response.locale()).isEqualTo("fr");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void create_whenOrganizationMissing_throwsAndDoesNotSave() {
        when(organizationRepository.existsById(orgId)).thenReturn(false);

        assertThatThrownBy(() -> restaurantService.create(request(), CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(scope.ownedRestaurant(id, CALLER))
                .thenThrow(new ResourceNotFoundException("Restaurant", id));

        assertThatThrownBy(() -> restaurantService.get(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_whenMissingOrSomeoneElses_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(scope.ownedRestaurant(id, CALLER))
                .thenThrow(new ResourceNotFoundException("Restaurant", id));

        assertThatThrownBy(() -> restaurantService.delete(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(restaurantRepository, never()).deleteById(any());
    }

    @Test
    void delete_whenOwned_deletes() {
        UUID id = UUID.randomUUID();
        when(scope.ownedRestaurant(id, CALLER)).thenReturn(Restaurant.builder().id(id).build());

        restaurantService.delete(id, CALLER);

        verify(restaurantRepository).deleteById(id);
    }

    @Test
    void list_withOrganizationId_filtersByOrganization() {
        when(scope.organizationOf(CALLER)).thenReturn(Optional.of(orgId));
        when(restaurantRepository.findByOrganizationId(orgId)).thenReturn(List.of());

        restaurantService.list(orgId, CALLER);

        verify(restaurantRepository).findByOrganizationId(orgId);
        verify(restaurantRepository, never()).findAll();
    }

    @Test
    void list_withoutFilter_returnsAll() {
        // No organization on the caller: the AI microservice, which serves every restaurant.
        when(scope.organizationOf(CALLER)).thenReturn(Optional.empty());
        when(restaurantRepository.findAll()).thenReturn(List.of());

        restaurantService.list(null, CALLER);

        verify(restaurantRepository).findAll();
    }

    @Test
    void list_neverWidensBeyondTheCallersOwnOrganization() {
        UUID otherOrganization = UUID.randomUUID();
        when(scope.organizationOf(CALLER)).thenReturn(Optional.of(orgId));
        when(restaurantRepository.findByOrganizationId(orgId)).thenReturn(List.of());

        restaurantService.list(otherOrganization, CALLER);

        // Asking for someone else's organization returns your own, not theirs.
        verify(restaurantRepository).findByOrganizationId(orgId);
        verify(restaurantRepository, never()).findByOrganizationId(otherOrganization);
    }
}
