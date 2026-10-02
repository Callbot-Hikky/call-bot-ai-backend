package com.callbot.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.dto.PublicReservationResponse;
import com.callbot.ai.dto.RescheduleSlotsResponse;
import com.callbot.ai.exception.BookingException;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.PublicBookingService;

@WebMvcTest(controllers = PublicBookingController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class PublicBookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicBookingService bookingService;

    private final UUID restaurantId = UUID.randomUUID();

    private PublicReservationResponse sample() {
        return new PublicReservationResponse(UUID.randomUUID(), restaurantId, "Chez Test",
                OffsetDateTime.parse("2030-01-01T19:30:00Z"), OffsetDateTime.parse("2030-01-01T21:00:00Z"),
                2, "pending", "Nadia", null);
    }

    @Test
    void slots_defaultsToTwoGuests() throws Exception {
        when(bookingService.slots(eq(restaurantId), eq(null), eq(2)))
                .thenReturn(new RescheduleSlotsResponse(List.of()));

        mockMvc.perform(get("/api/public/restaurants/" + restaurantId + "/slots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").isArray());
    }

    @Test
    void create_withValidPayload_returns201AndTheReservation() throws Exception {
        when(bookingService.create(eq(restaurantId), any())).thenReturn(sample());

        mockMvc.perform(post("/api/public/restaurants/" + restaurantId + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsAt":"2030-01-01T19:30:00Z","partySize":2,
                         "customer":{"firstName":"Nadia","phone":"06 12 34 56 78"},"notes":""}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.customerFirstName").value("Nadia"))
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void create_withoutNameOrWithSixteenGuests_is400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/public/restaurants/" + restaurantId + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsAt":"2030-01-01T19:30:00Z","partySize":16,
                         "customer":{"firstName":"  ","phone":"abc"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.partySize").exists())
                .andExpect(jsonPath("$.fieldErrors['customer.firstName']").exists())
                .andExpect(jsonPath("$.fieldErrors['customer.phone']").exists());
    }

    @Test
    void create_whenNoTable_is409WithStableCode() throws Exception {
        when(bookingService.create(eq(restaurantId), any()))
                .thenThrow(new BookingException(HttpStatus.CONFLICT, "no_table", "No table is available for that slot"));

        mockMvc.perform(post("/api/public/restaurants/" + restaurantId + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsAt":"2030-01-01T19:30:00Z","partySize":2,
                         "customer":{"firstName":"Nadia","phone":"0612345678"}}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_table"));
    }

    @Test
    void reschedule_withValidPayload_returnsTheUpdatedPublicView() throws Exception {
        PublicReservationResponse r = sample();
        when(bookingService.reschedule(eq(r.id()), any())).thenReturn(r);

        mockMvc.perform(put("/api/public/reservations/" + r.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsAt":"2030-01-01T19:30:00Z","partySize":2,"notes":""}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Chez Test"));
    }

    @Test
    void rescheduleSlots_passThePartySize() throws Exception {
        UUID id = UUID.randomUUID();
        when(bookingService.rescheduleSlots(id, 4)).thenReturn(new RescheduleSlotsResponse(List.of()));

        mockMvc.perform(get("/api/public/reservations/" + id + "/slots?partySize=4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").isArray());
    }

    @Test
    void get_returnsThePublicView() throws Exception {
        PublicReservationResponse r = sample();
        when(bookingService.get(r.id())).thenReturn(r);

        mockMvc.perform(get("/api/public/reservations/" + r.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Chez Test"));
    }
}
