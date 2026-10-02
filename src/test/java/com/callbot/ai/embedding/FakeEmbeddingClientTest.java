package com.callbot.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class FakeEmbeddingClientTest {

    private final FakeEmbeddingClient client = new FakeEmbeddingClient(64);

    @Test
    void producesVectorsOfTheConfiguredDimension_withUnitLength() {
        float[] vector = client.embedQuery("plat sans gluten pour enfant");

        assertThat(vector).hasSize(64);
        assertThat(norm(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void isDeterministic() {
        assertThat(client.embedQuery("terrasse chauffée"))
                .containsExactly(client.embedQuery("terrasse chauffée"));
    }

    @Test
    void textsSharingWords_areCloserThanUnrelatedOnes() {
        List<float[]> docs = client.embedDocuments(List.of(
                "Nous proposons des plats sans gluten et sans lactose",
                "Le parking est gratuit le soir après 19h"));
        float[] query = client.embedQuery("avez-vous des plats sans gluten ?");

        assertThat(cosine(query, docs.get(0))).isGreaterThan(cosine(query, docs.get(1)));
    }

    @Test
    void emptyText_stillYieldsAValidVector() {
        float[] vector = client.embedQuery("   ");
        assertThat(norm(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    private static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot; // both unit vectors
    }
}
