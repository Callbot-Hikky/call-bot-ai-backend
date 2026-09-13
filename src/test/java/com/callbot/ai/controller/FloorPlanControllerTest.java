package com.callbot.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.callbot.ai.dto.FloorPlanResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.RestaurantAccess;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.FloorPlanService;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = FloorPlanController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class FloorPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FloorPlanService floorPlanService;
    @MockitoBean
    private RestaurantAccess restaurantAccess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FloorPlanResponse sample(UUID restaurantId) throws Exception {
        return new FloorPlanResponse(
                restaurantId,
                objectMapper.readTree("""
                        {"version":2,"geometry":{"t1":{"x":0.5,"y":0.5}},"walls":[]}"""),
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    @Test
    void get_returns200WithLayout() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(floorPlanService.get(eq(restaurantId), any())).thenReturn(sample(restaurantId));

        mockMvc.perform(get("/api/floor-plans/{id}", restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantId").value(restaurantId.toString()))
                .andExpect(jsonPath("$.layout.version").value(2))
                .andExpect(jsonPath("$.layout.geometry.t1.x").value(0.5));
    }

    @Test
    void get_whenMissing_returns404() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(floorPlanService.get(eq(restaurantId), any()))
                .thenThrow(new ResourceNotFoundException("FloorPlan", restaurantId));

        mockMvc.perform(get("/api/floor-plans/{id}", restaurantId))
                .andExpect(status().isNotFound());
    }

    @Test
    void upsert_withValidPayload_returns200() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(floorPlanService.upsert(eq(restaurantId), any(), any())).thenReturn(sample(restaurantId));

        mockMvc.perform(put("/api/floor-plans/{id}", restaurantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"layout":{"version":2,"geometry":{},"walls":[]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layout.version").value(2));
    }

    @Test
    void upsert_withoutLayout_returns400() throws Exception {
        mockMvc.perform(put("/api/floor-plans/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/floor-plans/{id}", UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }
}
