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
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private OrganizationRepository organizationRepository;
    @InjectMocks
    private RestaurantService restaurantService;

    private final UUID orgId = UUID.randomUUID();

    private RestaurantRequest request() {
        return new RestaurantRequest(orgId, "Chez Test", "+33100000001",
                null, null, null, null, null, null, null);
    }

    @Test
    void create_whenOrganizationExists_appliesDefaultsAndSaves() {
        when(organizationRepository.existsById(orgId)).thenReturn(true);
        when(restaurantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        RestaurantResponse response = restaurantService.create(request());

        assertThat(response.name()).isEqualTo("Chez Test");
        assertThat(response.timezone()).isEqualTo("Europe/Paris");
        assertThat(response.locale()).isEqualTo("fr");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void create_whenOrganizationMissing_throwsAndDoesNotSave() {
        when(organizationRepository.existsById(orgId)).thenReturn(false);

        assertThatThrownBy(() -> restaurantService.create(request()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(restaurantRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.get(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_whenMissing_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(restaurantRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> restaurantService.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(restaurantRepository, never()).deleteById(any());
    }

    @Test
    void delete_whenExists_deletes() {
        UUID id = UUID.randomUUID();
        when(restaurantRepository.existsById(id)).thenReturn(true);

        restaurantService.delete(id);

        verify(restaurantRepository).deleteById(id);
    }

    @Test
    void list_withOrganizationId_filtersByOrganization() {
        when(restaurantRepository.findByOrganizationId(orgId)).thenReturn(List.of());

        restaurantService.list(orgId);

        verify(restaurantRepository).findByOrganizationId(orgId);
        verify(restaurantRepository, never()).findAll();
    }

    @Test
    void list_withoutFilter_returnsAll() {
        when(restaurantRepository.findAll()).thenReturn(List.of());

        restaurantService.list(null);

        verify(restaurantRepository).findAll();
    }
}
