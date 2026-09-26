package com.callbot.ai.support;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.callbot.ai.model.OfferSubscription;
import com.callbot.ai.repository.OfferSubscriptionRepository;

/**
 * Base class for integration tests. Boots the full Spring context against a
 * PostgreSQL container, so Flyway and JPA run exactly as in production.
 *
 * <p>The container follows the singleton pattern: started once in a static
 * initializer and shared across every test class for the whole JVM run (Ryuk
 * stops it at the end). This keeps the mapped port stable, which matters because
 * Spring caches the application context between test classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @Autowired
    private OfferSubscriptionRepository offerSubscriptionRepository;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * Stands in for a completed Stripe checkout: most integration tests exercise
     * restaurant/reservation features, not the paywall itself, so they register a user
     * and go straight to creating a restaurant the way a paying customer would after
     * checkout. Without this, every one of them would 402 on {@code POST /api/restaurants}
     * (see {@code RestaurantAccess#requireActiveSubscription}). Tests of the paywall itself
     * skip this and assert the 402 directly.
     */
    protected void activateSubscription(UUID organizationId) {
        offerSubscriptionRepository.save(OfferSubscription.builder()
                .organizationId(organizationId)
                .offerCode("pro")
                .amountCents(9900)
                .status(OfferSubscription.STATUS_ACTIVE)
                .build());
    }
}
