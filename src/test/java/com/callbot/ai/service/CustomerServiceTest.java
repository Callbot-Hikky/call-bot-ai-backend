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

import com.callbot.ai.dto.CustomerRequest;
import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Customer;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.security.OrganizationScope;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrganizationScope scope;
    @InjectMocks
    private CustomerService customerService;

    private static final String CALLER = "owner@resto.fr";

    private final UUID restaurantId = UUID.randomUUID();

    private CustomerRequest request() {
        return new CustomerRequest(restaurantId, "+33600000000", "Alice", "Martin",
                "alice@example.com", null);
    }

    @Test
    void create_whenRestaurantExists_saves() {
        when(customerRepository.findByRestaurantIdAndPhone(restaurantId, "+33600000000"))
                .thenReturn(Optional.empty());
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CustomerResponse response = customerService.create(request(), CALLER);

        assertThat(response.phone()).isEqualTo("+33600000000");
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.email()).isEqualTo("alice@example.com");
    }

    @Test
    void create_whenPhoneAlreadyExists_reusesExistingCustomer() {
        Customer existing = Customer.builder()
                .id(UUID.randomUUID())
                .restaurantId(restaurantId)
                .phone("+33600000000")
                .firstName("Alice")
                .build();
        when(customerRepository.findByRestaurantIdAndPhone(restaurantId, "+33600000000"))
                .thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CustomerResponse response = customerService.create(request(), CALLER);

        // Meme fiche reutilisee (pas de nouveau customer -> pas de violation d'unicite).
        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.phone()).isEqualTo("+33600000000");
    }

    @Test
    void create_whenRestaurantMissing_throwsAndDoesNotSave() {
        doThrow(new ResourceNotFoundException("Restaurant", restaurantId))
                .when(scope).requireOwnedRestaurant(restaurantId, CALLER);

        assertThatThrownBy(() -> customerService.create(request(), CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(customerRepository, never()).save(any());
    }

    @Test
    void get_whenMissing_throws() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.get(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_whenMissing_throwsAndDoesNotDelete() {
        UUID id = UUID.randomUUID();
        when(customerRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.delete(id, CALLER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(customerRepository, never()).deleteById(any());
    }

    @Test
    void list_withRestaurantId_filters() {
        when(customerRepository.findByRestaurantId(restaurantId)).thenReturn(List.of());

        customerService.list(restaurantId, CALLER);

        verify(customerRepository).findByRestaurantId(restaurantId);
        verify(customerRepository, never()).findAll();
    }
}
