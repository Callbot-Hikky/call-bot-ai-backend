package com.callbot.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.callbot.ai.config.EmbeddingProperties;

/** Drives the client against a mocked HTTP server: no network, no real key. */
class VoyageEmbeddingClientTest {

    private static final String BASE = "https://voyage.test/v1";

    private MockRestServiceServer server;
    private VoyageEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new VoyageEmbeddingClient(builder,
                new EmbeddingProperties("secret-key", BASE, "voyage-4-lite", 3));
    }

    @Test
    void embedQuery_sendsQueryInputType_andParsesVector() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andExpect(jsonPath("$.model").value("voyage-4-lite"))
                .andExpect(jsonPath("$.input_type").value("query"))
                .andExpect(jsonPath("$.output_dimension").value(3))
                .andExpect(jsonPath("$.input[0]").value("une terrasse ?"))
                .andRespond(withSuccess("""
                        {"object":"list","model":"voyage-4-lite",
                         "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}],
                         "usage":{"total_tokens":4}}""", MediaType.APPLICATION_JSON));

        float[] vector = client.embedQuery("une terrasse ?");

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
        server.verify();
    }

    @Test
    void embedDocuments_sendsDocumentInputType_andKeepsInputOrder() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andExpect(jsonPath("$.input_type").value("document"))
                .andExpect(jsonPath("$.input.length()").value(2))
                // Provider answers out of order: we must re-sort on index.
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0,0,1]},
                                 {"index":0,"embedding":[1,0,0]}]}""", MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embedDocuments(List.of("carte", "horaires"));

        assertThat(vectors.get(0)).containsExactly(1f, 0f, 0f);
        assertThat(vectors.get(1)).containsExactly(0f, 0f, 1f);
    }

    @Test
    void embedDocuments_withNoTexts_doesNotCallTheProvider() {
        assertThat(client.embedDocuments(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void wrongDimension_isRejected_soTheDbInsertNeverFails() {
        server.expect(requestTo(BASE + "/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.1,0.2]}]}""", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedQuery("x"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("3 dimensions");
    }
}
