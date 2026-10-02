package com.callbot.ai.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.KnowledgeEntryRequest;
import com.callbot.ai.dto.KnowledgeEntryResponse;
import com.callbot.ai.dto.KnowledgeSearchResponse;
import com.callbot.ai.embedding.EmbeddingClient;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.KnowledgeEntry;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.KnowledgeEntryRepository;
import com.callbot.ai.repository.RestaurantRepository;

import lombok.RequiredArgsConstructor;

/**
 * What the voice assistant may say beyond bookings: free text written or validated
 * by the restaurateur, retrieved by similarity to the caller's question.
 *
 * <p>The assistant only ever sends and receives text. Embeddings are computed here,
 * so the same model is guaranteed on the write side and the search side.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class KnowledgeService {

    /** Entries handed to the assistant per question: it runs a small model with a short context. */
    public static final int DEFAULT_LIMIT = 3;
    public static final int MAX_LIMIT = 5;

    private final KnowledgeEntryRepository entryRepository;
    private final RestaurantRepository restaurantRepository;
    private final EmbeddingClient embeddingClient;

    @Transactional(readOnly = true)
    public List<KnowledgeEntryResponse> list(UUID restaurantId) {
        return entryRepository.findByRestaurantIdOrderByUpdatedAtDesc(restaurantId).stream()
                .map(KnowledgeEntryResponse::from)
                .toList();
    }

    public KnowledgeEntryResponse create(UUID restaurantId, KnowledgeEntryRequest request) {
        return KnowledgeEntryResponse.from(
                create(restaurantId, request.title(), request.content(), KnowledgeEntry.SOURCE_MANUAL));
    }

    KnowledgeEntry create(UUID restaurantId, String title, String content, String source) {
        // Embed first: if the provider is down nothing is written.
        String embedding = embed(title, content);
        KnowledgeEntry entry = entryRepository.saveAndFlush(KnowledgeEntry.builder()
                .restaurantId(restaurantId)
                .title(title.trim())
                .content(content.trim())
                .source(source)
                .build());
        entryRepository.setEmbedding(entry.getId(), embedding);
        return entry;
    }

    public KnowledgeEntryResponse update(UUID restaurantId, UUID entryId, KnowledgeEntryRequest request) {
        KnowledgeEntry entry = find(restaurantId, entryId);
        String embedding = embed(request.title(), request.content());
        entry.setTitle(request.title().trim());
        entry.setContent(request.content().trim());
        KnowledgeEntry saved = entryRepository.saveAndFlush(entry);
        entryRepository.setEmbedding(saved.getId(), embedding);
        return KnowledgeEntryResponse.from(saved);
    }

    public void delete(UUID restaurantId, UUID entryId) {
        entryRepository.delete(find(restaurantId, entryId));
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse search(UUID restaurantId, String question, Integer limit) {
        int max = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        String query = toVectorLiteral(embeddingClient.embedQuery(question));
        List<KnowledgeSearchResponse.Match> matches = entryRepository.findNearest(restaurantId, query, max)
                .stream()
                .map(m -> new KnowledgeSearchResponse.Match(m.getId(), m.getTitle(), m.getContent(),
                        toScore(m.getDistance())))
                .toList();
        return new KnowledgeSearchResponse(question, matches);
    }

    @Transactional(readOnly = true)
    public KnowledgeSearchResponse searchByPhone(String restaurantPhone, String question, Integer limit) {
        Restaurant restaurant = restaurantRepository.findByPhoneNumber(restaurantPhone)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", restaurantPhone));
        return search(restaurant.getId(), question, limit);
    }

    private KnowledgeEntry find(UUID restaurantId, UUID entryId) {
        return entryRepository.findByIdAndRestaurantId(entryId, restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeEntry", entryId));
    }

    /** Title and content are embedded together: the title often carries the caller's own words. */
    private String embed(String title, String content) {
        String text = title.trim() + "\n" + content.trim();
        return toVectorLiteral(embeddingClient.embedDocuments(List.of(text)).get(0));
    }

    /** Cosine distance (0..2) to a similarity in 0..1. */
    private static double toScore(Double distance) {
        double score = 1.0 - (distance == null ? 1.0 : distance);
        return Math.round(Math.max(0.0, Math.min(1.0, score)) * 1000.0) / 1000.0;
    }

    /** pgvector's text form: {@code [0.1,0.2,...]}. */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
