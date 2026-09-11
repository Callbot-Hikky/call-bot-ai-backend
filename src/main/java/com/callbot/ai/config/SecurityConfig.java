package com.callbot.ai.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
    private final CorsProperties corsProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
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
                        // Seules les deux URL publiques du menu sont anonymes : un futur controleur
                        // sous /api/public restera protege tant qu'il n'est pas liste ici.
                        .requestMatchers(HttpMethod.GET, "/api/public/restaurants/*/menu",
                                "/api/public/restaurants/*/menu/files/*").permitAll()
                        // Payment webhook: unauthenticated, trust is the provider's signed webhook header.
                        .requestMatchers(HttpMethod.POST, "/api/offers/webhook").permitAll()
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
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);

        return source;
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
