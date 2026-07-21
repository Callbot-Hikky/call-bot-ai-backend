package com.callbot.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** An empty {@code apiKey} (the default) disables API-key authentication. */
@ConfigurationProperties(prefix = "app.service")
public record ServiceProperties(String apiKey) {
}
