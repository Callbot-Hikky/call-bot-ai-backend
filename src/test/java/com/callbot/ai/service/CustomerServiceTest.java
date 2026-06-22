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

import com.callbot.ai.dto.CustomerRequest;
import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @InjectMocks
    private CustomerService customerService;

    private final UUID restaurantId = UUID.randomUUID();

    private CustomerRequest request() {
        return new CustomerRequest(restaurantId, "+33600000000", "Alice", "Martin",
                "alice@example.com", null);
    }

    @Test
    void create_whenRestaurantExists_saves() {
        when(restaurantRepository.existsById(restaurantId)).thenReturn(true);
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CustomerResponse response = customerService.create(request());

        assertThat(response.phone()).isEqualTo("+33600000000");
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.email()).isEqualTo("alice@example.com");
    }

    @Test
    void create_whenRestaurantMissing_throwsAndDoesNotSave() {
        when(restaurantRepository.existsById(restaurantId)).thenReturn(false);

        assertThatThrownBy(() -> customerService.create(request()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(customerRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.get(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_whenMissing_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(customerRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> customerService.delete(id))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(customerRepository, never()).deleteById(any());
    }

    @Test
    void list_withRestaurantId_filters() {
        when(customerRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());

        customerService.list(restaurantId);

        verify(customerRepository).findByRestaurantId(restaurantId);
        verify(customerRepository, never()).findAll();
    }
}
