package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.dto.RestaurantHoursRequest;
import com.callbot.ai.dto.RestaurantHoursResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.repository.RestaurantHoursRepository;
import com.callbot.ai.security.OrganizationScope;

@ExtendWith(MockitoExtension.class)
class RestaurantHoursServiceTest {

    @Mock
    private RestaurantHoursRepository hoursRepository;
    @Mock
    private OrganizationScope scope;
    @InjectMocks
    private RestaurantHoursService hoursService;

    private static final String CALLER = "owner@resto.fr";

    private final UUID restaurantId = UUID.randomUUID();

    private RestaurantHoursRequest request() {
        return new RestaurantHoursRequest(restaurantId, (short) 1, "dinner",
                LocalTime.of(19, 0), LocalTime.of(23, 0), null);
    }

    @Test
    void create_whenRestaurantExists_savesWithDefaultNotClosed() {
        
        when(hoursRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        RestaurantHoursResponse response = hoursService.create(request(), CALLER);

        assertThat(response.service()).isEqualTo("dinner");
        assertThat(response.dayOfWeek()).isEqualTo((short) 1);
        assertThat(response.isClosed()).isFalse();
    }

    @Test
    void create_whenRestaurantMissing_throwsAndDoesNotSave() {
        doThrow(new ResourceNotFoundException("Restaurant", restaurantId))
                .when(scope).requireOwnedRestaurant(restaurantId, CALLER);

        assertThatThrownBy(() -> hoursService.create(request(), CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(hoursRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(hoursRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> hoursService.get(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_whenMissing_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(hoursRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> hoursService.delete(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(hoursRepository, never()).deleteById(any());
    }

    @Test
    void list_withRestaurantId_filters() {
        when(hoursRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());

        hoursService.list(restaurantId, CALLER);

        verify(hoursRepository).findByRestaurantId(restaurantId);
        verify(hoursRepository, never()).findAll();
    }
}
