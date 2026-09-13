package com.callbot.ai.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.RestaurantTableRequest;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantTableService {

    private final RestaurantTableRepository tableRepository;
    private final RestaurantRepository restaurantRepository;

    public RestaurantTableResponse create(RestaurantTableRequest request) {
        requireRestaurant(request.restaurantId());
        RestaurantTable table = RestaurantTable.builder()
                .restaurantId(request.restaurantId())
                .name(request.name())
                .capacity(request.capacity())
                .zone(request.zone())
                .isActive(request.isActive() != null ? request.isActive() : true)
                .build();
        return RestaurantTableResponse.from(tableRepository.save(table));
    }

    @Transactional(readOnly = true)
    public List<RestaurantTableResponse> listOwned(Set<UUID> restaurantIds) {
        if (restaurantIds.isEmpty()) {
            return List.of();
        }
        return tableRepository.findByRestaurantIdIn(restaurantIds).stream().map(RestaurantTableResponse::from).toList();
    }

    public List<RestaurantTableResponse> list(UUID restaurantId) {
        List<RestaurantTable> tables = restaurantId != null
                ? tableRepository.findByRestaurantId(restaurantId)
                : tableRepository.findAll();
        return tables.stream().map(RestaurantTableResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RestaurantTableResponse get(UUID id) {
        return RestaurantTableResponse.from(find(id));
    }

    public RestaurantTableResponse update(UUID id, RestaurantTableRequest request) {
        RestaurantTable table = find(id);
        table.setName(request.name());
        table.setCapacity(request.capacity());
        table.setZone(request.zone());
        if (request.isActive() != null) {
            table.setIsActive(request.isActive());
        }
        return RestaurantTableResponse.from(tableRepository.save(table));
    }

    public void delete(UUID id) {
        if (!tableRepository.existsById(id)) {
            throw new ResourceNotFoundException("Table", id);
        }
        tableRepository.deleteById(id);
    }

    private RestaurantTable find(UUID id) {
        return tableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Table", id));
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }
}
