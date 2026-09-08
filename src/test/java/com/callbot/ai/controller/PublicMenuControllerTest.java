package com.callbot.ai.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.dto.MenuFileResponse;
import com.callbot.ai.dto.PublicMenuResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.RestaurantMenuFile;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.MenuService;

@WebMvcTest(controllers = PublicMenuController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class PublicMenuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MenuService menuService;

    @Test
    void getPublic_returns200WithNameModeAndFiles() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(menuService.getPublic(restaurantId)).thenReturn(new PublicMenuResponse(
                "Chez Hikky", "images", null,
                List.of(new MenuFileResponse(fileId, "image", "image/jpeg", 0, 1234,
                        "/api/public/restaurants/" + restaurantId + "/menu/files/" + fileId))));

        mockMvc.perform(get("/api/public/restaurants/{id}/menu", restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Chez Hikky"))
                .andExpect(jsonPath("$.mode").value("images"))
                .andExpect(jsonPath("$.files[0].url").value("/api/public/restaurants/" + restaurantId + "/menu/files/" + fileId))
                .andExpect(jsonPath("$.phoneNumber").doesNotExist());
    }

    @Test
    void getPublic_whenRestaurantMissing_returns404() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(menuService.getPublic(restaurantId)).thenThrow(new ResourceNotFoundException("Restaurant", restaurantId));

        mockMvc.perform(get("/api/public/restaurants/{id}/menu", restaurantId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getFile_streamsBytesWithDetectedContentTypeAndSafeHeaders() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        byte[] data = "%PDF-1.7 fake".getBytes();
        when(menuService.getFile(restaurantId, fileId)).thenReturn(RestaurantMenuFile.builder()
                .id(fileId).restaurantId(restaurantId).kind("pdf").position(0)
                .contentType("application/pdf").sizeBytes(data.length).data(data).build());

        mockMvc.perform(get("/api/public/restaurants/{id}/menu/files/{fileId}", restaurantId, fileId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(content().bytes(data));
    }
}
