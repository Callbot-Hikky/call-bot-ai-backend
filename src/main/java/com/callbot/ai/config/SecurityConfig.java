package com.callbot.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.callbot.ai.security.JwtAuthenticationFilter;
import com.callbot.ai.security.ServiceApiKeyFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/**",
            "/api/ping",
            "/actuator/health"
    };

    /** Fichiers de menu servis en binaire, admin et publics (voir MenuFileHttp). */
    private static final RequestMatcher MENU_FILE_URLS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.GET, "/api/restaurants/*/menu/files/*"),
            PathPatternRequestMatcher.withDefaults()
                    .matcher(HttpMethod.GET, "/api/public/restaurants/*/menu/files/*"));

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ServiceApiKeyFilter serviceApiKeyFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // DENY partout, sauf les fichiers de menu (PDF, images) que nos propres pages
                // affichent dans un cadre : meme origine seulement.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                MENU_FILE_URLS,
                                new StaticHeadersWriter("X-Frame-Options", "SAMEORIGIN")))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(MENU_FILE_URLS),
                                new StaticHeadersWriter("X-Frame-Options", "DENY"))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        // Lecture publique du menu (lien dans le message de confirmation, QR code).
                        .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
                        // Call endpoints reserved for the AI microservice (API key).
                        .requestMatchers(HttpMethod.POST, "/api/calls/ingest").hasRole("SERVICE")
                        .requestMatchers(HttpMethod.GET, "/api/calls/context", "/api/calls/availability")
                        .hasRole("SERVICE")
                        .anyRequest().authenticated())
                .addFilterBefore(serviceApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
