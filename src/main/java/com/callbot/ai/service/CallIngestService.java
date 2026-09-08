package com.callbot.ai.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CallIngestRequest;
import com.callbot.ai.dto.CallIngestRequest.Booking;
import com.callbot.ai.dto.CallIngestRequest.Caller;
import com.callbot.ai.dto.CallIngestResponse;
import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Call;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.notification.ReservationCreatedEvent;
import com.callbot.ai.repository.CallRepository;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * Persists the outcome of a call in a single transaction: restaurant lookup,
 * customer upsert, call and reservation creation.
 *
 * <p>Idempotent on {@code twilioCallSid}: an already-ingested call returns its
 * existing reservation instead of creating a new one.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CallIngestService {

    private final RestaurantRepository restaurantRepository;
    private final CustomerRepository customerRepository;
    private final CallRepository callRepository;
    private final ReservationRepository reservationRepository;
    private final GuaranteePolicy guaranteePolicy;
    private final ApplicationEventPublisher events;

    public CallIngestResponse ingest(CallIngestRequest request) {
        Optional<Call> alreadyIngested = callRepository.findByTwilioCallSid(request.twilioCallSid());
        if (alreadyIngested.isPresent()) {
            Call call = alreadyIngested.get();
            ReservationResponse reservation = reservationRepository.findByCallId(call.getId())
                    .map(ReservationResponse::from)
                    .orElse(null);
            return new CallIngestResponse(call.getId(), call.getCustomerId(), reservation, true);
        }

        Restaurant restaurant = restaurantRepository.findByPhoneNumber(request.restaurantPhone())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", request.restaurantPhone()));

        Customer customer = upsertCustomer(restaurant.getId(), request.customer());

        Call call = callRepository.save(Call.builder()
                .restaurantId(restaurant.getId())
                .customerId(customer.getId())
                .twilioCallSid(request.twilioCallSid())
                .fromNumber(request.fromNumber())
                .toNumber(request.restaurantPhone())
                .direction("inbound")
                .status("completed")
                .outcome("reservation_created")
                .captured(true)
                .build());

        Booking booking = request.reservation();
        Reservation reservation = Reservation.builder()
                .restaurantId(restaurant.getId())
                .customerId(customer.getId())
                .tableId(booking.tableId())
                .callId(call.getId())
                .startsAt(booking.startsAt())
                .endsAt(booking.endsAt())
                .partySize(booking.partySize())
                .source("callbot")
                .notes(booking.notes())
                .build();
        // The phone is where reservations actually come from, so the guarantee has to be
        // applied here too — and the diner has to be told, which is what the event does.
        guaranteePolicy.applyOnCreation(reservation, restaurant, null);
        Reservation saved = reservationRepository.save(reservation);
        events.publishEvent(new ReservationCreatedEvent(saved.getId()));

        return new CallIngestResponse(call.getId(), customer.getId(),
                ReservationResponse.from(saved), false);
    }

    /** Name and email are only updated when provided, to avoid overwriting data with null. */
    private Customer upsertCustomer(UUID restaurantId, Caller caller) {
        Customer customer = customerRepository.findByRestaurantIdAndPhone(restaurantId, caller.phone())
                .orElseGet(() -> Customer.builder()
                        .restaurantId(restaurantId)
                        .phone(caller.phone())
                        .build());
        if (caller.firstName() != null) {
            customer.setFirstName(caller.firstName());
        }
        if (caller.lastName() != null) {
            customer.setLastName(caller.lastName());
        }
        if (caller.email() != null) {
            customer.setEmail(caller.email());
        }
        return customerRepository.save(customer);
    }
}
