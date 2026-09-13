package com.callbot.ai.controller;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.CallIngestRequest;
import com.callbot.ai.dto.CallIngestRequest.Booking;
import com.callbot.ai.dto.CallIngestResponse;
import com.callbot.ai.exception.DatabaseConstraints;
import com.callbot.ai.exception.SlotTakenException;
import com.callbot.ai.service.CallContextService;
import com.callbot.ai.service.CallIngestService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Write endpoint called by the AI microservice at the end of a call. */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
public class CallIngestController {

    private final CallIngestService callIngestService;
    private final CallContextService callContextService;

    @PostMapping("/ingest")
    @ResponseStatus(HttpStatus.CREATED)
    public CallIngestResponse ingest(@Valid @RequestBody CallIngestRequest request) {
        try {
            return callIngestService.ingest(request);
        } catch (DataIntegrityViolationException ex) {
            if (!DatabaseConstraints.violates(ex, DatabaseConstraints.NO_OVERLAPPING_RESERVATION)) {
                throw ex;
            }
            // The table was taken between the availability check and this call.
            // The ingest transaction is already rolled back at this point, so the
            // availability lookup below runs in a fresh, read-only one. We answer
            // with alternatives so the AI can counter-propose instead of just failing.
            Booking booking = request.reservation();
            throw new SlotTakenException(callContextService
                    .availability(request.restaurantPhone(), booking.startsAt(), booking.endsAt(),
                            booking.partySize())
                    .alternatives());
        }
    }
}
