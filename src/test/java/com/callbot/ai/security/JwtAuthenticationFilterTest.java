package com.callbot.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;

import jakarta.servlet.http.Cookie;

class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(jwtService, userDetailsService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void acceptToken(String token, String subject) {
        when(jwtService.isValid(token)).thenReturn(true);
        when(jwtService.extractSubject(token)).thenReturn(subject);
        when(userDetailsService.loadUserByUsername(subject))
                .thenReturn(new User(subject, "", List.of()));
    }

    @Test
    void authenticatesFromCookieWhenNoAuthorizationHeader() throws Exception {
        acceptToken("tok-cookie", "bob@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("hikky_token", "tok-cookie"));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("bob@example.com");
    }

    @Test
    void stillAuthenticatesFromBearerHeader() throws Exception {
        acceptToken("tok-header", "alice@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tok-header");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void leavesContextEmptyWhenNoTokenAnywhere() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void ignoresBlankCookie() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("hikky_token", ""));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
