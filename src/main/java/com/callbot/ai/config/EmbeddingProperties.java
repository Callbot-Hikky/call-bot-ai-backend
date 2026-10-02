package com.callbot.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding provider settings. An empty {@code apiKey} (the default) switches to
 * a deterministic in-process fake, so dev and tests never need network access.
 */
@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
        String apiKey,
        @DefaultValue("https://api.voyageai.com/v1") String baseUrl,
        @DefaultValue("voyage-4-lite") String model,
        @DefaultValue("1024") int dimension) {
}
