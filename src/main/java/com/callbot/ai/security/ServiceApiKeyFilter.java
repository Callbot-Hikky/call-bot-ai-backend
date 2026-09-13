package com.callbot.ai.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.callbot.ai.config.ServiceProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Authenticates the AI microservice via the {@code X-Api-Key} header, granting
 * the SERVICE role. Inert when {@code app.service.api-key} is empty.
 */
@Component
@RequiredArgsConstructor
public class ServiceApiKeyFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-Api-Key";
    private static final String SERVICE_PRINCIPAL = "ai-service";

    private final ServiceProperties serviceProperties;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String configuredKey = serviceProperties.apiKey();
        String providedKey = request.getHeader(API_KEY_HEADER);

        if (StringUtils.hasText(configuredKey)
                && matches(configuredKey, providedKey)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    SERVICE_PRINCIPAL, null,
                    List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Constant-time comparison. {@link String#equals} returns as soon as a byte
     * differs, so response times would leak how many leading characters of the
     * key an attacker has guessed right.
     */
    static boolean matches(String configuredKey, String providedKey) {
        if (providedKey == null) {
            return false;
        }
        return MessageDigest.isEqual(
                configuredKey.getBytes(StandardCharsets.UTF_8),
                providedKey.getBytes(StandardCharsets.UTF_8));
    }
}
