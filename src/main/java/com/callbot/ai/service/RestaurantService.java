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
import com.callbot.ai.security.OrganizationScope;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationScope scope;

    public RestaurantResponse create(RestaurantRequest request, String callerEmail) {
        if (!organizationRepository.existsById(request.organizationId())) {
            throw new ResourceNotFoundException("Organization", request.organizationId());
        }
        requireOwnOrganization(request.organizationId(), callerEmail);
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

    /**
     * A signed-in caller only ever sees their own organization's restaurants, whatever
     * they pass as a filter: the parameter narrows the list, it cannot widen it.
     */
    @Transactional(readOnly = true)
    public List<RestaurantResponse> list(UUID organizationId, String callerEmail) {
        UUID effective = scope.organizationOf(callerEmail).orElse(organizationId);
        List<Restaurant> restaurants = effective != null
                ? restaurantRepository.findByOrganizationId(effective)
                : restaurantRepository.findAll();
        return restaurants.stream().map(RestaurantResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RestaurantResponse get(UUID id, String callerEmail) {
        return RestaurantResponse.from(scope.ownedRestaurant(id, callerEmail));
    }

    public RestaurantResponse update(UUID id, RestaurantRequest request, String callerEmail) {
        Restaurant restaurant = scope.ownedRestaurant(id, callerEmail);
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

    public void delete(UUID id, String callerEmail) {
        scope.ownedRestaurant(id, callerEmail);
        restaurantRepository.deleteById(id);
    }

    /** A restaurant may only be created under the caller's own organization. */
    private void requireOwnOrganization(UUID organizationId, String callerEmail) {
        scope.organizationOf(callerEmail).ifPresent(caller -> {
            if (!caller.equals(organizationId)) {
                throw new ResourceNotFoundException("Organization", organizationId);
            }
        });
    }

    public RestaurantResponse updateAttributes(UUID id, Map<String, Object> attributes,
            String callerEmail) {
        Restaurant restaurant = scope.ownedRestaurant(id, callerEmail);
        restaurant.setAttributes(attributes);
        return RestaurantResponse.from(restaurantRepository.save(restaurant));
    }
}
