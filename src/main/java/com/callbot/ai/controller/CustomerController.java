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
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CustomerRequest request) {
        try {
            return customerService.create(request);
        } catch (DataIntegrityViolationException race) {
            // Deux creations simultanees du meme (restaurant, phone) : celle qui
            // perd la course relit la fiche gagnante au lieu de renvoyer un 409.
            return customerService.findByPhone(request.restaurantId(), request.phone());
        }
    }

    @GetMapping
    public List<CustomerResponse> list(@RequestParam(required = false) UUID restaurantId) {
        return customerService.list(restaurantId);
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return customerService.get(id);
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest request) {
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        customerService.delete(id);
    }
}
