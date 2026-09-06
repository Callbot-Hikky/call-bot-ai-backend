package com.callbot.ai.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

public final class AuthCookie {

    public static final String NAME = "hikky_token";
    private static final Duration MAX_AGE = Duration.ofDays(7);

    private AuthCookie() {
    }

    public static ResponseCookie session(String token) {
        return base(token, MAX_AGE);
    }

    public static ResponseCookie cleared() {
        return base("", Duration.ZERO);
    }

    private static ResponseCookie base(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
