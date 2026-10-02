package com.callbot.ai.embedding;

import java.util.List;

/**
 * Turns text into a fixed-size vector. Documents (stored entries) and queries
 * (caller questions) are embedded differently by retrieval models, hence two
 * methods rather than one.
 */
public interface EmbeddingClient {

    /** Embeds texts that will be stored and searched against. */
    List<float[]> embedDocuments(List<String> texts);

    /** Embeds a question that will be matched against stored documents. */
    float[] embedQuery(String text);

    /** Size of every vector this client produces. Must match the DB column. */
    int dimension();
}
