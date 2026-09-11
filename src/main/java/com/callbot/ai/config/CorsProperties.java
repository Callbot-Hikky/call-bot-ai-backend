package com.callbot.ai.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-origin config. {@code allowedOrigins} lists the frontend origins allowed to call
 * the API from a browser (e.g. the Angular dev server, the deployed app domain).
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
