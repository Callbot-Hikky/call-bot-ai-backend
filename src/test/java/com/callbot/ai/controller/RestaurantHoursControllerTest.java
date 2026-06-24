package com.callbot.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
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

import com.callbot.ai.dto.RestaurantHoursResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.RestaurantHoursService;

@WebMvcTest(controllers = RestaurantHoursController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class RestaurantHoursControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestaurantHoursService hoursService;

    private RestaurantHoursResponse sample() {
        return new RestaurantHoursResponse(UUID.randomUUID(), UUID.randomUUID(), (short) 1,
                "dinner", LocalTime.of(19, 0), LocalTime.of(23, 0), false);
    }

    @Test
    void create_withValidPayload_returns201() throws Exception {
        when(hoursService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/api/restaurant-hours")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","dayOfWeek":1,"service":"dinner",
                         "opensAt":"19:00","closesAt":"23:00"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.service").value("dinner"));
    }

    @Test
    void create_withInvalidPayload_returns400() throws Exception {
        mockMvc.perform(post("/api/restaurant-hours")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void get_returns200() throws Exception {
        when(hoursService.get(any())).thenReturn(sample());

        mockMvc.perform(get("/api/restaurant-hours/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dayOfWeek").value(1));
    }

    @Test
    void get_whenNotFound_returns404() throws Exception {
        when(hoursService.get(any()))
                .thenThrow(new ResourceNotFoundException("RestaurantHours", UUID.randomUUID()));

        mockMvc.perform(get("/api/restaurant-hours/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/restaurant-hours/" + UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }
}
