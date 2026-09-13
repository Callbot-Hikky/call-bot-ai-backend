package com.callbot.ai.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

    // pgvector image (PostgreSQL 16 + the "vector" extension used by the knowledge base).
    // asCompatibleSubstituteFor tells Testcontainers to drive it like a stock postgres image.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
