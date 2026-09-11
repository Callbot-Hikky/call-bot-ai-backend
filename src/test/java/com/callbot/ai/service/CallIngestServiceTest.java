package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.callbot.ai.dto.CallIngestRequest;
import com.callbot.ai.dto.CallIngestRequest.Booking;
import com.callbot.ai.dto.CallIngestRequest.Caller;
import com.callbot.ai.dto.CallIngestResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Call;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.CallRepository;
import com.callbot.ai.repository.CustomerRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;

@ExtendWith(MockitoExtension.class)
class CallIngestServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CallRepository callRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private GuaranteePolicy guaranteePolicy;
    @Mock
    private ApplicationEventPublisher events;
    @InjectMocks
    private CallIngestService callIngestService;

    private static final String SID = "CA-test-123";
    private static final String RESTO_PHONE = "+33100000001";
    private static final String CALLER_PHONE = "+33600000000";

    private CallIngestRequest request() {
        return new CallIngestRequest(SID, RESTO_PHONE, CALLER_PHONE,
                new Caller(CALLER_PHONE, "Alice", null, null),
                new Booking(null, null,
                        OffsetDateTime.parse("2030-01-01T19:00:00Z"),
                        OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                        2, null));
    }

    private Restaurant restaurant() {
        return Restaurant.builder().id(UUID.randomUUID()).build();
    }

    @Test
    void ingest_whenNew_createsCustomerCallAndReservation() {
        when(callRepository.findByTwilioCallSid(SID)).thenReturn(Optional.empty());
        when(restaurantRepository.findByPhoneNumber(RESTO_PHONE)).thenReturn(Optional.of(restaurant()));
        when(customerRepository.findByRestaurantIdAndPhone(any(), any())).thenReturn(Optional.empty());
        when(customerRepository.save(any())).thenAnswer(i -> withId(i.getArgument(0), Customer::setId, Customer::getId));
        when(callRepository.save(any())).thenAnswer(i -> withId(i.getArgument(0), Call::setId, Call::getId));
        when(reservationRepository.save(any()))
                .thenAnswer(i -> withId(i.getArgument(0), Reservation::setId, Reservation::getId));

        CallIngestResponse response = callIngestService.ingest(request());

        assertThat(response.alreadyProcessed()).isFalse();
        assertThat(response.callId()).isNotNull();
        assertThat(response.customerId()).isNotNull();
        assertThat(response.reservation()).isNotNull();
        assertThat(response.reservation().partySize()).isEqualTo(2);
    }

    @Test
    void ingest_whenSidAlreadyProcessed_isIdempotent() {
        UUID callId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Call existing = Call.builder().id(callId).customerId(customerId).twilioCallSid(SID).build();
        Reservation reservation = Reservation.builder().id(UUID.randomUUID()).callId(callId)
                .startsAt(OffsetDateTime.now()).endsAt(OffsetDateTime.now()).partySize(2).build();
        when(callRepository.findByTwilioCallSid(SID)).thenReturn(Optional.of(existing));
        when(reservationRepository.findByCallId(callId)).thenReturn(Optional.of(reservation));

        CallIngestResponse response = callIngestService.ingest(request());

        assertThat(response.alreadyProcessed()).isTrue();
        assertThat(response.callId()).isEqualTo(callId);
        assertThat(response.customerId()).isEqualTo(customerId);
        verify(restaurantRepository, never()).findByPhoneNumber(any());
        verify(customerRepository, never()).save(any());
        verify(callRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void ingest_whenRestaurantPhoneUnknown_throwsAndCreatesNothing() {
        when(callRepository.findByTwilioCallSid(SID)).thenReturn(Optional.empty());
        when(restaurantRepository.findByPhoneNumber(RESTO_PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> callIngestService.ingest(request()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(callRepository, never()).save(any());
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void ingest_whenCustomerExists_reusesItInsteadOfCreating() {
        UUID existingCustomerId = UUID.randomUUID();
        Customer existing = Customer.builder().id(existingCustomerId)
                .phone(CALLER_PHONE).firstName("Bob").build();
        when(callRepository.findByTwilioCallSid(SID)).thenReturn(Optional.empty());
        when(restaurantRepository.findByPhoneNumber(RESTO_PHONE)).thenReturn(Optional.of(restaurant()));
        when(customerRepository.findByRestaurantIdAndPhone(any(), any())).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(callRepository.save(any())).thenAnswer(i -> withId(i.getArgument(0), Call::setId, Call::getId));
        when(reservationRepository.save(any()))
                .thenAnswer(i -> withId(i.getArgument(0), Reservation::setId, Reservation::getId));

        CallIngestResponse response = callIngestService.ingest(request());

        assertThat(response.customerId()).isEqualTo(existingCustomerId);
    }

    // Assigns a random id to the entity if it has none, mimicking JPA on save.
    private <T> T withId(T entity, IdSetter<T> setter, IdGetter<T> getter) {
        if (getter.get(entity) == null) {
            setter.set(entity, UUID.randomUUID());
        }
        return entity;
    }

    private interface IdSetter<T> {
        void set(T entity, UUID id);
    }

    private interface IdGetter<T> {
        UUID get(T entity);
    }
}
