package com.callbot.ai.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.callbot.ai.config.JwtProperties;

class JwtServiceTest {

    private static final String SECRET = "0123456789012345678901234567890123456789";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(new JwtProperties(SECRET, 3_600_000L));
    }

    @Test
    void generatedTokenCarriesSubjectAndValidates() {
        String token = jwtService.generateToken("alice@example.com");

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractSubject(token)).isEqualTo("alice@example.com");
    }

    @Test
    void malformedTokenIsRejected() {
        assertThat(jwtService.isValid("not.a.real.jwt")).isFalse();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expiringService = new JwtService(new JwtProperties(SECRET, -1_000L));
        String expiredToken = expiringService.generateToken("alice@example.com");

        assertThat(jwtService.isValid(expiredToken)).isFalse();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtService otherService = new JwtService(
                new JwtProperties("abcdefghijabcdefghijabcdefghijabcdefghij", 3_600_000L));
        String foreignToken = otherService.generateToken("alice@example.com");

        assertThat(jwtService.isValid(foreignToken)).isFalse();
    }
}
