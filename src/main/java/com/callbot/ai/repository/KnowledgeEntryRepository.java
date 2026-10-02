package com.callbot.ai.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.callbot.ai.model.KnowledgeEntry;

public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, UUID> {

    List<KnowledgeEntry> findByRestaurantIdOrderByUpdatedAtDesc(UUID restaurantId);

    Optional<KnowledgeEntry> findByIdAndRestaurantId(UUID id, UUID restaurantId);

    /** {@code embedding} is pgvector's text form, e.g. {@code [0.1,0.2,...]}. */
    @Modifying
    @Query(value = "UPDATE knowledge_base_entries SET embedding = cast(:embedding AS vector) WHERE id = :id",
            nativeQuery = true)
    void setEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    /** Nearest entries of one restaurant by cosine distance (0 = identical, 2 = opposite). */
    @Query(value = """
            SELECT e.id AS id, e.title AS title, e.content AS content,
                   (e.embedding <=> cast(:query AS vector)) AS distance
            FROM knowledge_base_entries e
            WHERE e.restaurant_id = :restaurantId
              AND e.embedding IS NOT NULL
            ORDER BY e.embedding <=> cast(:query AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Match> findNearest(@Param("restaurantId") UUID restaurantId, @Param("query") String query,
            @Param("limit") int limit);

    interface Match {
        UUID getId();

        String getTitle();

        String getContent();

        Double getDistance();
    }
}
