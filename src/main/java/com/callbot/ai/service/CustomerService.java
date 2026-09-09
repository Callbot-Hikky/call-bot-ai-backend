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
import com.callbot.ai.security.OrganizationScope;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrganizationScope scope;

    public CustomerResponse create(CustomerRequest request, String callerEmail) {
        scope.requireOwnedRestaurant(request.restaurantId(), callerEmail);
        // Upsert par (restaurant, phone) : un habitue (deja appele par le callbot
        // ou reserve auparavant) reutilise sa fiche au lieu de violer la contrainte
        // d'unicite. On ne remplace que les champs fournis (pas d'ecrasement par null).
        Customer customer = customerRepository
                .findByRestaurantIdAndPhone(request.restaurantId(), request.phone())
                .orElseGet(() -> Customer.builder()
                        .restaurantId(request.restaurantId())
                        .phone(request.phone())
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
    public CustomerResponse findByPhone(UUID restaurantId, String phone, String callerEmail) {
        scope.requireOwnedRestaurant(restaurantId, callerEmail);
        return customerRepository.findByRestaurantIdAndPhone(restaurantId, phone)
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", phone));
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> list(UUID restaurantId, String callerEmail) {
        List<Customer> customers = listFor(restaurantId, callerEmail);
        return customers.stream().map(CustomerResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id, String callerEmail) {
        Customer entity = find(id);
        requireOwned(entity, id, callerEmail);
        return CustomerResponse.from(entity);
    }

    public CustomerResponse update(UUID id, CustomerRequest request, String callerEmail) {
        Customer customer = find(id);
        requireOwned(customer, id, callerEmail);
        customer.setPhone(request.phone());
        customer.setFirstName(request.firstName());
        customer.setLastName(request.lastName());
        customer.setEmail(request.email());
        customer.setNotes(request.notes());
        return CustomerResponse.from(customerRepository.save(customer));
    }

    public void delete(UUID id, String callerEmail) {
        requireOwned(find(id), id, callerEmail);
        customerRepository.deleteById(id);
    }

    private Customer find(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    /** The caller may only reach customers under a restaurant they own. */
    private void requireOwned(Customer entity, UUID id, String callerEmail) {
        scope.requireOwnedThrough(entity.getRestaurantId(), "Customer", id, callerEmail);
    }

    /**
     * The restaurant filter narrows the list; it can never widen it. A signed-in caller
     * asking for someone else's restaurant gets nothing, not that restaurant's data.
     */
    private List<Customer> listFor(UUID restaurantId, String callerEmail) {
        if (restaurantId != null) {
            scope.requireOwnedRestaurant(restaurantId, callerEmail);
            return customerRepository.findByRestaurantId(restaurantId);
        }
        return scope.ownedRestaurantIds(callerEmail)
                .map(customerRepository::findByRestaurantIdIn)
                .orElseGet(customerRepository::findAll);
    }

}
