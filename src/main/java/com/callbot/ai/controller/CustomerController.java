package com.callbot.ai.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.CustomerRequest;
import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.service.CustomerService;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.callbot.ai.security.RestaurantAccess;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final RestaurantAccess restaurantAccess;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CustomerRequest request, Authentication authentication) {
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        try {
            return customerService.create(request);
        } catch (DataIntegrityViolationException race) {
            // Deux creations simultanees du meme (restaurant, phone) : celle qui
            // perd la course relit la fiche gagnante au lieu de renvoyer un 409.
            return customerService.findByPhone(request.restaurantId(), request.phone());
        }
    }

    @GetMapping
    public List<CustomerResponse> list(@RequestParam(required = false) UUID restaurantId,
            Authentication authentication) {
        if (restaurantId != null) {
            restaurantAccess.requireOwned(restaurantId, authentication);
            return customerService.list(restaurantId);
        }
        Set<UUID> owned = restaurantAccess.ownedRestaurantIds(authentication);
        return customerService.list(null).stream().filter(c -> owned.contains(c.restaurantId())).toList();
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id, Authentication authentication) {
        CustomerResponse customer = customerService.get(id);
        restaurantAccess.requireOwned(customer.restaurantId(), authentication);
        return customer;
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request,
            Authentication authentication) {
        restaurantAccess.requireOwned(customerService.get(id).restaurantId(), authentication);
        restaurantAccess.requireOwned(request.restaurantId(), authentication);
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication authentication) {
        restaurantAccess.requireOwned(customerService.get(id).restaurantId(), authentication);
        customerService.delete(id);
    }
}
