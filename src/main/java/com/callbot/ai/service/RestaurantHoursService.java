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
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantHoursService {

    private final RestaurantHoursRepository hoursRepository;
    private final RestaurantRepository restaurantRepository;

    public RestaurantHoursResponse create(RestaurantHoursRequest request) {
        requireRestaurant(request.restaurantId());
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
    public List<RestaurantHoursResponse> list(UUID restaurantId) {
        List<RestaurantHours> hours = restaurantId != null
                ? hoursRepository.findByRestaurantId(restaurantId)
                : hoursRepository.findAll();
        return hours.stream().map(RestaurantHoursResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RestaurantHoursResponse get(UUID id) {
        return RestaurantHoursResponse.from(find(id));
    }

    public RestaurantHoursResponse update(UUID id, RestaurantHoursRequest request) {
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

    public void delete(UUID id) {
        if (!hoursRepository.existsById(id)) {
            throw new ResourceNotFoundException("RestaurantHours", id);
        }
        hoursRepository.deleteById(id);
    }

    private RestaurantHours find(UUID id) {
        return hoursRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RestaurantHours", id));
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }
}
