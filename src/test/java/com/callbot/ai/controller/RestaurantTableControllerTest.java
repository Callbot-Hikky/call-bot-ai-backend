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

import com.callbot.ai.dto.RestaurantTableResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.RestaurantTableService;

@WebMvcTest(controllers = RestaurantTableController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class RestaurantTableControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestaurantTableService tableService;

    private RestaurantTableResponse sample() {
        return new RestaurantTableResponse(UUID.randomUUID(), UUID.randomUUID(), "T1", 4,
                null, true, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void create_withValidPayload_returns201() throws Exception {
        when(tableService.create(any(), any())).thenReturn(sample());

        mockMvc.perform(post("/api/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","name":"T1","capacity":4}""".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("T1"));
    }

    @Test
    void create_withInvalidPayload_returns400() throws Exception {
        mockMvc.perform(post("/api/tables")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }

    @Test
    void get_returns200() throws Exception {
        when(tableService.get(any(), any())).thenReturn(sample());

        mockMvc.perform(get("/api/tables/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(4));
    }

    @Test
    void get_whenNotFound_returns404() throws Exception {
        when(tableService.get(any(), any()))
                .thenThrow(new ResourceNotFoundException("Table", UUID.randomUUID()));

        mockMvc.perform(get("/api/tables/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/tables/" + UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }
}
