package com.callbot.ai.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CustomerRequest;
import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Customer;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.util.PhoneNumbers;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;

    public CustomerResponse create(CustomerRequest request) {
        requireRestaurant(request.restaurantId());
        // Upsert par (restaurant, phone) : un habitue (deja appele par le callbot
        // ou reserve auparavant) reutilise sa fiche au lieu de violer la contrainte
        // d'unicite. On ne remplace que les champs fournis (pas d'ecrasement par null).
        String phone = PhoneNumbers.normalize(request.phone());
        Customer customer = customerRepository
                .findByRestaurantIdAndPhone(request.restaurantId(), phone)
                .orElseGet(() -> Customer.builder()
                        .restaurantId(request.restaurantId())
                        .phone(phone)
                        .build());
        if (request.firstName() != null) {
            customer.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            customer.setLastName(request.lastName());
        }
        if (request.email() != null) {
            customer.setEmail(request.email());
        }
        if (request.notes() != null) {
            customer.setNotes(request.notes());
        }
        return CustomerResponse.from(customerRepository.save(customer));
    }

    // Relecture par (restaurant, phone) : sert au controller pour recuperer la
    // fiche gagnante quand deux creations concurrentes du meme numero se croisent.
    @Transactional(readOnly = true)
    public CustomerResponse findByPhone(UUID restaurantId, String phone) {
        return customerRepository.findByRestaurantIdAndPhone(restaurantId, PhoneNumbers.normalize(phone))
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", phone));
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> listOwned(Set<UUID> restaurantIds) {
        if (restaurantIds.isEmpty()) {
            return List.of();
        }
        return customerRepository.findByRestaurantIdIn(restaurantIds).stream().map(CustomerResponse::from).toList();
    }

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
        customer.setPhone(PhoneNumbers.normalize(request.phone()));
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
