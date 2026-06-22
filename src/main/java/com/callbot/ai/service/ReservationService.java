package com.callbot.ai.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.ReservationRequest;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final RestaurantRepository restaurantRepository;

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
        return ReservationResponse.from(reservationRepository.save(reservation));
    }

    @Transactional(readOnly = true)
    public List<ReservationResponse> list(UUID restaurantId) {
        List<Reservation> reservations = restaurantId != null
                ? reservationRepository.findByRestaurantId(restaurantId)
                : reservationRepository.findAll();
        return reservations.stream().map(ReservationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(UUID id) {
        return ReservationResponse.from(find(id));
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
