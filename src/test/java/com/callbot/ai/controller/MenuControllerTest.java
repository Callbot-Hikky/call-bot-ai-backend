package com.callbot.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.dto.MenuLimits;
import com.callbot.ai.dto.MenuResponse;
import com.callbot.ai.exception.MenuFileException;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.RestaurantAccess;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.MenuService;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = MenuController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class MenuControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MenuService menuService;

    @MockitoBean
    private RestaurantAccess restaurantAccess;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MenuResponse sample(UUID restaurantId) {
        return new MenuResponse(restaurantId, "manual",
                objectMapper.readTree("""
                        {"version":1,"sections":[]}"""),
                List.of(), MenuLimits.DEFAULT);
    }

    @Test
    void get_returns200WithMenu() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(menuService.get(restaurantId)).thenReturn(sample(restaurantId));

        mockMvc.perform(get("/api/restaurants/{id}/menu", restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("manual"))
                .andExpect(jsonPath("$.limits.imageMaxCount").value(8));
    }

    @Test
    void get_whenNotOwner_returns403() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(restaurantAccess.requireOwned(eq(restaurantId), any()))
                .thenThrow(new AccessDeniedException("not yours"));

        mockMvc.perform(get("/api/restaurants/{id}/menu", restaurantId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void upsert_withValidPayload_returns200() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(menuService.upsert(eq(restaurantId), any())).thenReturn(sample(restaurantId));

        mockMvc.perform(put("/api/restaurants/{id}/menu", restaurantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"manual","manual":{"version":1,"sections":[]}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manual.version").value(1));
    }

    @Test
    void upsert_withUnknownMode_returns400() throws Exception {
        mockMvc.perform(put("/api/restaurants/{id}/menu", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"video"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.mode").exists());
    }

    @Test
    void upload_returns200WithMenu() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(menuService.upload(eq(restaurantId), any())).thenReturn(sample(restaurantId));
        MockMultipartFile file = new MockMultipartFile("file", "carte.pdf", "application/pdf", "%PDF-1.7 test".getBytes());

        mockMvc.perform(multipart("/api/restaurants/{id}/menu/files", restaurantId).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("manual"));
    }

    @Test
    void upload_unsupportedType_returns415() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        when(menuService.upload(eq(restaurantId), any())).thenThrow(
                new MenuFileException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_file_type", "nope"));
        MockMultipartFile file = new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg/>".getBytes());

        mockMvc.perform(multipart("/api/restaurants/{id}/menu/files", restaurantId).file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("unsupported_file_type"));
    }

    @Test
    void deleteFile_returns200WithMenu() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        when(menuService.deleteFile(restaurantId, fileId)).thenReturn(sample(restaurantId));

        mockMvc.perform(delete("/api/restaurants/{id}/menu/files/{fileId}", restaurantId, fileId))
                .andExpect(status().isOk());
    }

    @Test
    void reorder_withEmptyList_returns400() throws Exception {
        mockMvc.perform(put("/api/restaurants/{id}/menu/files/order", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fileIds":[]}"""))
                .andExpect(status().isBadRequest());
    }
}
