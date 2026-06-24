package com.callbot.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Machine-to-machine authentication for the AI microservice. When {@code apiKey}
 * is empty (the default), API-key authentication is disabled.
 */
@ConfigurationProperties(prefix = "app.service")
public record ServiceProperties(String apiKey) {
}
