package com.callbot.ai.service;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.RestaurantRequest;
import com.callbot.ai.dto.RestaurantResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final OrganizationRepository organizationRepository;

    public RestaurantResponse create(RestaurantRequest request) {
        if (!organizationRepository.existsById(request.organizationId())) {
            throw new ResourceNotFoundException("Organization", request.organizationId());
        }
        Restaurant restaurant = Restaurant.builder()
                .organizationId(request.organizationId())
                .name(request.name())
                .phoneNumber(request.phoneNumber())
                .address(request.address())
                .city(request.city())
                .postalCode(request.postalCode())
                .timezone(request.timezone() != null ? request.timezone() : "Europe/Paris")
                .locale(request.locale() != null ? request.locale() : "fr")
                .isActive(request.isActive() != null ? request.isActive() : true)
                .attributes(request.attributes() != null ? request.attributes() : new HashMap<>())
                .build();
        return RestaurantResponse.from(restaurantRepository.save(restaurant));
    }

    @Transactional(readOnly = true)
    public List<RestaurantResponse> list(UUID organizationId) {
        List<Restaurant> restaurants = organizationId != null
                ? restaurantRepository.findByOrganizationId(organizationId)
                : restaurantRepository.findAll();
        return restaurants.stream().map(RestaurantResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RestaurantResponse get(UUID id) {
        return RestaurantResponse.from(find(id));
    }

    public RestaurantResponse update(UUID id, RestaurantRequest request) {
        Restaurant restaurant = find(id);
        restaurant.setName(request.name());
        restaurant.setPhoneNumber(request.phoneNumber());
        restaurant.setAddress(request.address());
        restaurant.setCity(request.city());
        restaurant.setPostalCode(request.postalCode());
        if (request.timezone() != null) {
            restaurant.setTimezone(request.timezone());
        }
        if (request.locale() != null) {
            restaurant.setLocale(request.locale());
        }
        if (request.isActive() != null) {
            restaurant.setIsActive(request.isActive());
        }
        if (request.attributes() != null) {
            restaurant.setAttributes(request.attributes());
        }
        return RestaurantResponse.from(restaurantRepository.save(restaurant));
    }

    public void delete(UUID id) {
        if (!restaurantRepository.existsById(id)) {
            throw new ResourceNotFoundException("Restaurant", id);
        }
        restaurantRepository.deleteById(id);
    }

    private Restaurant find(UUID id) {
        return restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", id));
    }

    public RestaurantResponse updateAttributes(UUID id, Map<String, Object> attributes) {
        Restaurant restaurant = find(id);
        restaurant.setAttributes(attributes);
        return RestaurantResponse.from(restaurantRepository.save(restaurant));
    }
}
