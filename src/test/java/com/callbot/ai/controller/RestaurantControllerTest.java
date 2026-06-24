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

import com.callbot.ai.dto.RestaurantResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.RestaurantService;

@WebMvcTest(controllers = RestaurantController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class RestaurantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestaurantService restaurantService;

    private RestaurantResponse sample() {
        return new RestaurantResponse(UUID.randomUUID(), UUID.randomUUID(), "Chez Test",
                "+33100000001", null, null, null, "Europe/Paris", "fr", true,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void create_withValidPayload_returns201() throws Exception {
        when(restaurantService.create(any())).thenReturn(sample());

        mockMvc.perform(post("/api/restaurants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Chez Test","phoneNumber":"+33100000001"}"""
                        .formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Chez Test"));
    }

    @Test
    void create_withInvalidPayload_returns400() throws Exception {
        mockMvc.perform(post("/api/restaurants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void get_returns200() throws Exception {
        when(restaurantService.get(any())).thenReturn(sample());

        mockMvc.perform(get("/api/restaurants/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+33100000001"));
    }

    @Test
    void get_whenNotFound_returns404() throws Exception {
        when(restaurantService.get(any()))
                .thenThrow(new ResourceNotFoundException("Restaurant", UUID.randomUUID()));

        mockMvc.perform(get("/api/restaurants/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/restaurants/" + UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }
}
