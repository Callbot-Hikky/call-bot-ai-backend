package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

import com.callbot.ai.dto.RestaurantTableRequest;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.security.OrganizationScope;
import com.callbot.ai.repository.RestaurantTableRepository;

@ExtendWith(MockitoExtension.class)
class RestaurantTableServiceTest {

    @Mock
    private RestaurantTableRepository tableRepository;
    @Mock
    private OrganizationScope scope;
    @InjectMocks
    private RestaurantTableService tableService;

    private static final String CALLER = "owner@resto.fr";

    private final UUID restaurantId = UUID.randomUUID();

    private RestaurantTableRequest request() {
        return new RestaurantTableRequest(restaurantId, "T1", 4, null, null);
    }

    @Test
    void create_whenRestaurantExists_savesWithDefaultActive() {
        
        when(tableRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        RestaurantTableResponse response = tableService.create(request(), CALLER);

        assertThat(response.name()).isEqualTo("T1");
        assertThat(response.capacity()).isEqualTo(4);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void create_whenRestaurantMissing_throwsAndDoesNotSave() {
        doThrow(new ResourceNotFoundException("Restaurant", restaurantId))
                .when(scope).requireOwnedRestaurant(restaurantId, CALLER);

        assertThatThrownBy(() -> tableService.create(request(), CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tableRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(tableRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tableService.get(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_whenMissing_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(tableRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tableService.delete(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tableRepository, never()).deleteById(any());
    }

    @Test
    void list_withRestaurantId_filters() {
        when(tableRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());

        tableService.list(restaurantId, CALLER);

        verify(tableRepository).findByRestaurantId(restaurantId);
        verify(tableRepository, never()).findAll();
    }
}
