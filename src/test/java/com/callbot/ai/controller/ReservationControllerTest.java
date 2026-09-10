package com.callbot.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.NoShowService;
import com.callbot.ai.service.ReservationService;

@WebMvcTest(controllers = ReservationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;
    @MockitoBean
    private NoShowService noShowService;

    private ReservationResponse sample() {
        return new ReservationResponse(UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                OffsetDateTime.parse("2030-01-01T19:00:00Z"),
                OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                2, "pending", "callbot", null,
                "none", "not_required", null, "eur", null,
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null, null);
    }

    @Test
    void create_withValidPayload_returns201() throws Exception {
        when(reservationService.create(any(), any())).thenReturn(sample());

        mockMvc.perform(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","startsAt":"2030-01-01T19:00:00Z",
                         "endsAt":"2030-01-01T21:00:00Z","partySize":2}""".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void create_withInvalidPayload_returns400() throws Exception {
        mockMvc.perform(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void get_returns200() throws Exception {
        when(reservationService.get(any(), any(), any())).thenReturn(sample());

        mockMvc.perform(get("/api/reservations/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(2));
    }

    @Test
    void get_whenNotFound_returns404() throws Exception {
        when(reservationService.get(any(), any(), any()))
                .thenThrow(new ResourceNotFoundException("Reservation", UUID.randomUUID()));

        mockMvc.perform(get("/api/reservations/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/reservations/" + UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }
}
