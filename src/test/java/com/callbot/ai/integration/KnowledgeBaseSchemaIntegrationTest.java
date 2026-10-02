package com.callbot.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.callbot.ai.embedding.EmbeddingClient;
import com.callbot.ai.support.AbstractIntegrationTest;

/**
 * Proves the pgvector setup end to end: the extension loads on the container
 * image, the V10 migration applies, and a cosine nearest-neighbour query ranks
 * the expected row first. Uses raw SQL on purpose: the JPA layer for the
 * knowledge base comes next and must not be a prerequisite for the schema.
 */
class KnowledgeBaseSchemaIntegrationTest extends AbstractIntegrationTest {

    /** Restaurant seeded by V8. */
    private static final UUID RESTAURANT_ID = UUID.fromString("22b60047-3341-4b71-bed8-e22bc08c3603");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Test
    void vectorExtensionIsInstalled_andColumnHasTheConfiguredDimension() {
        Integer installed = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        assertThat(installed).isEqualTo(1);

        Integer dimension = jdbc.queryForObject("""
                SELECT atttypmod FROM pg_attribute
                WHERE attrelid = 'knowledge_base_entries'::regclass AND attname = 'embedding'
                """, Integer.class);
        assertThat(dimension).isEqualTo(embeddingClient.dimension());
    }

    @Test
    void nearestNeighbourQuery_ranksTheMatchingEntryFirst() {
        insert("Allergènes", "Nous proposons des plats sans gluten et sans lactose sur demande");
        insert("Parking", "Le parking est gratuit le soir après 19h");
        insert("Animaux", "Les chiens sont acceptés en terrasse uniquement");

        String question = toLiteral(embeddingClient.embedQuery("avez-vous des plats sans gluten ?"));
        List<String> ranked = jdbc.queryForList("""
                SELECT title FROM knowledge_base_entries
                WHERE restaurant_id = ?
                ORDER BY embedding <=> ?::vector
                LIMIT 3
                """, String.class, RESTAURANT_ID, question);

        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(0)).isEqualTo("Allergènes");
    }

    private void insert(String title, String content) {
        float[] vector = embeddingClient.embedDocuments(List.of(content)).get(0);
        jdbc.update("""
                INSERT INTO knowledge_base_entries (restaurant_id, title, content, embedding)
                VALUES (?, ?, ?, ?::vector)
                """, RESTAURANT_ID, title, content, toLiteral(vector));
    }

    /** pgvector's text form: "[0.1,0.2,...]". */
    private static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
