package com.callbot.ai.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CustomerRequest;
import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Customer;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;

    public CustomerResponse create(CustomerRequest request) {
        requireRestaurant(request.restaurantId());
        Customer customer = Customer.builder()
                .restaurantId(request.restaurantId())
                .phone(request.phone())
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .notes(request.notes())
                .build();
        return CustomerResponse.from(customerRepository.save(customer));
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> list(UUID restaurantId) {
        List<Customer> customers = restaurantId != null
                ? customerRepository.findByRestaurantId(restaurantId)
                : customerRepository.findAll();
        return customers.stream().map(CustomerResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) {
        return CustomerResponse.from(find(id));
    }

    public CustomerResponse update(UUID id, CustomerRequest request) {
        Customer customer = find(id);
        customer.setPhone(request.phone());
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setEmail(request.email());
        customer.setNotes(request.notes());
        return CustomerResponse.from(customerRepository.save(customer));
    }

    public void delete(UUID id) {
        if (!customerRepository.existsById(id)) {
            throw new ResourceNotFoundException("Customer", id);
        }
        customerRepository.deleteById(id);
    }

    private Customer find(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }
}
