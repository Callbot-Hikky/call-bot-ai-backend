package com.callbot.ai.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.callbot.ai.dto.AuthResponse;
import com.callbot.ai.dto.LoginRequest;
import com.callbot.ai.dto.RegisterRequest;
import com.callbot.ai.security.AuthCookie;
import com.callbot.ai.service.AuthService;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        AuthResponse auth = authService.register(request);
        setSessionCookie(response, auth.accessToken());
        return auth;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        AuthResponse auth = authService.login(request);
        setSessionCookie(response, auth.accessToken());
        return auth;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookie.cleared().toString());
    }

    private void setSessionCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, AuthCookie.session(token).toString());
    }
}
