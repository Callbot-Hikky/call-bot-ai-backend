package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private RestaurantTableRepository tableRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private ReservationService reservationService;

    private final UUID restaurantId = UUID.randomUUID();

    private ReservationRequest request() {
        return new ReservationRequest(restaurantId, null, null, null,
                OffsetDateTime.parse("2030-01-01T19:00:00Z"),
                OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                2, null, null, null);
    }

    @Test
    void create_whenRestaurantExists_appliesDefaultStatusAndSource() {
        when(restaurantRepository.existsById(restaurantId)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ReservationResponse response = reservationService.create(request());

        assertThat(response.partySize()).isEqualTo(2);
        assertThat(response.status()).isEqualTo("pending");
        assertThat(response.source()).isEqualTo("callbot");
    }

    @Test
    void create_whenRestaurantMissing_throwsAndDoesNotSave() {
        when(restaurantRepository.existsById(restaurantId)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.create(request()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reservationService.get(id, Set.of()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_whenMissing_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> reservationService.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(reservationRepository, never()).deleteById(any());
    }

    @Test
    void list_withRestaurantId_filters() {
        when(reservationRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());

        reservationService.list(restaurantId, Set.of());

        verify(reservationRepository).findByRestaurantId(restaurantId);
        verify(reservationRepository, never()).findAll();
    }
}
