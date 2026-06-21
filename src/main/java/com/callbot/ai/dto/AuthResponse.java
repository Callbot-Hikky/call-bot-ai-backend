package com.callbot.ai.dto;

public record AuthResponse(String accessToken, String tokenType) {

    public static AuthResponse bearer(String accessToken) {
        return new AuthResponse(accessToken, "Bearer");
    }
}
