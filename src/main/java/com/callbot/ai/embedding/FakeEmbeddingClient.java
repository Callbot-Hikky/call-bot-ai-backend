package com.callbot.ai.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, offline embedding for dev and tests.
 *
 * <p>Each word of the text is hashed into a bucket of the vector, so texts that
 * share words end up closer in cosine distance. This is enough to exercise the
 * search pipeline end to end ("the entry about gluten ranks first for a gluten
 * question") without pretending to be a real model.
 */
public class FakeEmbeddingClient implements EmbeddingClient {

    private final int dimension;

    public FakeEmbeddingClient(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(text);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private float[] embed(String text) {
        float[] vector = new float[dimension];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.isBlank()) {
                continue;
            }
            vector[bucket(word)] += 1f;
        }
        return normalize(vector);
    }

    /** SHA-256 rather than hashCode(), so the bucket is stable across JVMs. */
    private int bucket(String word) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(word.getBytes(StandardCharsets.UTF_8));
            int value = ((digest[0] & 0xff) << 16) | ((digest[1] & 0xff) << 8) | (digest[2] & 0xff);
            return value % dimension;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Unit length, like real retrieval embeddings, so cosine and dot product agree. */
    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) {
            vector[0] = 1f;
            return vector;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }
}
