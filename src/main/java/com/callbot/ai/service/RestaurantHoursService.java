package com.callbot.ai.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.RestaurantHoursRequest;
import com.callbot.ai.dto.RestaurantHoursResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.RestaurantHours;
import com.callbot.ai.repository.RestaurantHoursRepository;
import com.callbot.ai.security.OrganizationScope;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantHoursService {

    private final RestaurantHoursRepository hoursRepository;
    private final OrganizationScope scope;

    public RestaurantHoursResponse create(RestaurantHoursRequest request, String callerEmail) {
        scope.requireOwnedRestaurant(request.restaurantId(), callerEmail);
        RestaurantHours hours = RestaurantHours.builder()
                .restaurantId(request.restaurantId())
                .dayOfWeek(request.dayOfWeek())
                .service(request.service())
                .opensAt(request.opensAt())
                .closesAt(request.closesAt())
                .isClosed(request.isClosed() != null ? request.isClosed() : false)
                .build();
        return RestaurantHoursResponse.from(hoursRepository.save(hours));
    }

    @Transactional(readOnly = true)
    public List<RestaurantHoursResponse> list(UUID restaurantId, String callerEmail) {
        List<RestaurantHours> hours = listFor(restaurantId, callerEmail);
        return hours.stream().map(RestaurantHoursResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RestaurantHoursResponse get(UUID id, String callerEmail) {
        RestaurantHours entity = find(id);
        requireOwned(entity, id, callerEmail);
        return RestaurantHoursResponse.from(entity);
    }

    public RestaurantHoursResponse update(UUID id, RestaurantHoursRequest request, String callerEmail) {
        RestaurantHours hours = find(id);
        hours.setDayOfWeek(request.dayOfWeek());
        hours.setService(request.service());
        hours.setOpensAt(request.opensAt());
        hours.setClosesAt(request.closesAt());
        if (request.isClosed() != null) {
            hours.setIsClosed(request.isClosed());
        }
        return RestaurantHoursResponse.from(hoursRepository.save(hours));
    }

    public void delete(UUID id, String callerEmail) {
        requireOwned(find(id), id, callerEmail);
        hoursRepository.deleteById(id);
    }

    private RestaurantHours find(UUID id) {
        return hoursRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RestaurantHours", id));
    }

    /** The caller may only reach restauranthourss under a restaurant they own. */
    private void requireOwned(RestaurantHours entity, UUID id, String callerEmail) {
        scope.requireOwnedThrough(entity.getRestaurantId(), "RestaurantHours", id, callerEmail);
    }

    /**
     * The restaurant filter narrows the list; it can never widen it. A signed-in caller
     * asking for someone else's restaurant gets nothing, not that restaurant's data.
     */
    private List<RestaurantHours> listFor(UUID restaurantId, String callerEmail) {
        if (restaurantId != null) {
            scope.requireOwnedRestaurant(restaurantId, callerEmail);
            return hoursRepository.findByRestaurantId(restaurantId);
        }
        return scope.ownedRestaurantIds(callerEmail)
                .map(hoursRepository::findByRestaurantIdIn)
                .orElseGet(hoursRepository::findAll);
    }

}
