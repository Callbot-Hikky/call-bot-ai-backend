package com.callbot.ai.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.RestaurantTableRequest;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.RestaurantTable;
import com.callbot.ai.security.OrganizationScope;
import com.callbot.ai.repository.RestaurantTableRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantTableService {

    private final RestaurantTableRepository tableRepository;
    private final OrganizationScope scope;

    public RestaurantTableResponse create(RestaurantTableRequest request, String callerEmail) {
        scope.requireOwnedRestaurant(request.restaurantId(), callerEmail);
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
    public List<RestaurantTableResponse> list(UUID restaurantId, String callerEmail) {
        List<RestaurantTable> tables = listFor(restaurantId, callerEmail);
        return tables.stream().map(RestaurantTableResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RestaurantTableResponse get(UUID id, String callerEmail) {
        RestaurantTable entity = find(id);
        requireOwned(entity, id, callerEmail);
        return RestaurantTableResponse.from(entity);
    }

    public RestaurantTableResponse update(UUID id, RestaurantTableRequest request, String callerEmail) {
        RestaurantTable table = find(id);
        table.setName(request.name());
        table.setCapacity(request.capacity());
        table.setZone(request.zone());
        if (request.isActive() != null) {
            table.setIsActive(request.isActive());
        }
        return RestaurantTableResponse.from(tableRepository.save(table));
    }

    public void delete(UUID id, String callerEmail) {
        requireOwned(find(id), id, callerEmail);
        tableRepository.deleteById(id);
    }

    private RestaurantTable find(UUID id) {
        return tableRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Table", id));
    }

    /** The caller may only reach restauranttables under a restaurant they own. */
    private void requireOwned(RestaurantTable entity, UUID id, String callerEmail) {
        scope.requireOwnedThrough(entity.getRestaurantId(), "RestaurantTable", id, callerEmail);
    }

    /**
     * The restaurant filter narrows the list; it can never widen it. A signed-in caller
     * asking for someone else's restaurant gets nothing, not that restaurant's data.
     */
    private List<RestaurantTable> listFor(UUID restaurantId, String callerEmail) {
        if (restaurantId != null) {
            scope.requireOwnedRestaurant(restaurantId, callerEmail);
            return tableRepository.findByRestaurantId(restaurantId);
        }
        return scope.ownedRestaurantIds(callerEmail)
                .map(tableRepository::findByRestaurantIdIn)
                .orElseGet(tableRepository::findAll);
    }

}
