package com.callbot.ai.embedding;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.callbot.ai.config.EmbeddingProperties;

/**
 * Voyage AI embeddings over HTTP ({@code POST /v1/embeddings}).
 *
 * <p>{@code input_type} is always sent: retrieval models prepend a different
 * instruction for documents and queries, and mixing them degrades ranking.
 */
public class VoyageEmbeddingClient implements EmbeddingClient {

    private static final String INPUT_TYPE_DOCUMENT = "document";
    private static final String INPUT_TYPE_QUERY = "query";

    private final RestClient http;
    private final EmbeddingProperties properties;

    public VoyageEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties) {
        this.properties = properties;
        this.http = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        return call(texts, INPUT_TYPE_DOCUMENT);
    }

    @Override
    public float[] embedQuery(String text) {
        return call(List.of(text), INPUT_TYPE_QUERY).get(0);
    }

    @Override
    public int dimension() {
        return properties.dimension();
    }

    private List<float[]> call(List<String> input, String inputType) {
        EmbeddingResponse response;
        try {
            response = http.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new EmbeddingRequest(input, properties.model(), inputType, properties.dimension()))
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (RestClientException e) {
            // Never leak the provider's message: it can echo the request, hence caller text.
            throw new EmbeddingException("Embedding provider call failed");
        }
        if (response == null || response.data() == null || response.data().size() != input.size()) {
            throw new EmbeddingException("Embedding provider returned "
                    + (response == null || response.data() == null ? 0 : response.data().size())
                    + " vectors for " + input.size() + " inputs");
        }
        // The API documents the order as matching the input; sort on index anyway.
        return response.data().stream()
                .sorted(Comparator.comparingInt(Embedding::index))
                .map(e -> toArray(e.embedding()))
                .toList();
    }

    private float[] toArray(List<Float> values) {
        if (values.size() != properties.dimension()) {
            throw new EmbeddingException("Expected " + properties.dimension()
                    + " dimensions, got " + values.size());
        }
        float[] array = new float[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /** Wire format of the Voyage request. Field names are the API's, hence snake_case. */
    record EmbeddingRequest(List<String> input, String model, String input_type, int output_dimension) {
    }

    record EmbeddingResponse(List<Embedding> data, String model, Usage usage) {
    }

    record Embedding(int index, List<Float> embedding) {
    }

    record Usage(int total_tokens) {
    }
}
