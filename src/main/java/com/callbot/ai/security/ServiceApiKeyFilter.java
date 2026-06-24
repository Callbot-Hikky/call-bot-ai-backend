package com.callbot.ai.security;

import java.io.IOException;
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
 * Authenticates machine-to-machine calls from the AI microservice via the
 * {@code X-Api-Key} header. A valid key yields an authentication with the SERVICE
 * role (distinct from human users authenticated by JWT).
 *
 * <p>When no key is configured ({@code app.service.api-key} empty), the filter is
 * inert: the request proceeds without service authentication.
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
                && configuredKey.equals(providedKey)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            var authentication = new UsernamePasswordAuthenticationToken(
                    SERVICE_PRINCIPAL, null,
                    List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
