package com.callbot.ai.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CustomerResponse;
import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.notification.ReservationCreatedEvent;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.RestaurantTableRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantTableRepository tableRepository;
    private final CustomerRepository customerRepository;
    private final ApplicationEventPublisher events;

    public ReservationResponse create(ReservationRequest request) {
        requireRestaurant(request.restaurantId());
        Reservation reservation = Reservation.builder()
                .restaurantId(request.restaurantId())
                .customerId(request.customerId())
                .tableId(request.tableId())
                .callId(request.callId())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .partySize(request.partySize())
                .status(request.status() != null ? request.status() : "pending")
                .source(request.source() != null ? request.source() : "callbot")
                .notes(request.notes())
                .build();
        Reservation saved = reservationRepository.save(reservation);
        events.publishEvent(new ReservationCreatedEvent(saved.getId()));
        return ReservationResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> list(UUID restaurantId, Set<String> expand) {
        List<Reservation> reservations = restaurantId != null
                ? reservationRepository.findByRestaurantId(restaurantId)
                : reservationRepository.findAll();
        return reservations.stream().map(r -> toResponse(r, expand)).toList();
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id, Set<String> expand) {
        return toResponse(find(id), expand);
    }

    public ReservationResponse update(UUID id, ReservationRequest request) {
        Reservation reservation = find(id);
        reservation.setCustomerId(request.customerId());
        reservation.setTableId(request.tableId());
        reservation.setCallId(request.callId());
        reservation.setStartsAt(request.startsAt());
        reservation.setEndsAt(request.endsAt());
        reservation.setPartySize(request.partySize());
        if (request.status() != null) {
            reservation.setStatus(request.status());
        }
        if (request.source() != null) {
            reservation.setSource(request.source());
        }
        reservation.setNotes(request.notes());
        return ReservationResponse.from(reservationRepository.save(reservation));
    }

    public void delete(UUID id) {
        if (!reservationRepository.existsById(id)) {
            throw new ResourceNotFoundException("Reservation", id);
        }
        reservationRepository.deleteById(id);
    }

    /**
     * Builds the response, embedding the related resources requested via ?expand=.
     * When not expanded (or when a link is null), table/customer stay null and are
     * omitted from the JSON.
     */
    private ReservationResponse toResponse(Reservation reservation, Set<String> expand) {
        RestaurantTableResponse table = null;
        CustomerResponse customer = null;
        if (expand.contains("table") && reservation.getTableId() != null) {
            table = tableRepository.findById(reservation.getTableId())
                    .map(RestaurantTableResponse::from)
                    .orElse(null);
        }
        if (expand.contains("customer") && reservation.getCustomerId() != null) {
            customer = customerRepository.findById(reservation.getCustomerId())
                    .map(CustomerResponse::from)
                    .orElse(null);
        }
        return ReservationResponse.from(reservation, table, customer);
    }

    private Reservation find(UUID id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", id));
    }

    private void requireRestaurant(UUID restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant", restaurantId);
        }
    }
}
