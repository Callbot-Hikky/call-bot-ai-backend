package com.callbot.ai.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.dto.AuthResponse;
import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;
import com.callbot.ai.service.AuthService;

@WebMvcTest(controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, ServiceApiKeyFilter.class}))
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    void register_withValidPayload_returns201AndToken() throws Exception {
        when(authService.register(any())).thenReturn(AuthResponse.bearer("token-123"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"bob@example.com","password":"password123"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("token-123"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void register_setsHttpOnlyCookie() throws Exception {
        when(authService.register(any())).thenReturn(AuthResponse.bearer("token-123"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"bob@example.com","password":"password123"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Set-Cookie", containsString("hikky_token=token-123")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
    }

    @Test
    void login_setsHttpOnlyCookie() throws Exception {
        when(authService.login(any())).thenReturn(AuthResponse.bearer("token-xyz"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"bob@example.com","password":"password123"}"""))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("hikky_token=token-xyz")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")));
    }

    @Test
    void logout_clearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("hikky_token=")))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void register_withInvalidPayload_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"not-an-email","password":"short"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }
}
