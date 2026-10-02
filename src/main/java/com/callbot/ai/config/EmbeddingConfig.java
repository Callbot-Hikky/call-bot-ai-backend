package com.callbot.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.callbot.ai.embedding.EmbeddingClient;
import com.callbot.ai.embedding.FakeEmbeddingClient;
import com.callbot.ai.embedding.VoyageEmbeddingClient;

/**
 * Picks the embedding implementation from configuration, mirroring how the
 * service API key works: no key configured means "run without the provider".
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    EmbeddingClient embeddingClient(EmbeddingProperties properties) {
        if (StringUtils.hasText(properties.apiKey())) {
            log.info("Embeddings: Voyage AI, model={} dimension={}", properties.model(), properties.dimension());
            return new VoyageEmbeddingClient(RestClient.builder(), properties);
        }
        log.warn("Embeddings: no app.embedding.api-key configured, using the offline fake "
                + "(fine for dev and tests, useless for real similarity search)");
        return new FakeEmbeddingClient(properties.dimension());
    }
}
