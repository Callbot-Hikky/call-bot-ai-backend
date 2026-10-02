package com.callbot.ai.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * The whole knowledge loop against a real PostgreSQL with pgvector: the restaurateur
 * writes entries, the assistant retrieves them by similarity, reports what it could
 * not answer, and the restaurateur's answer becomes retrievable in turn.
 * Embeddings come from the offline fake (no key configured in tests).
 */
@TestPropertySource(properties = "app.service.api-key=test-service-key")
class KnowledgeBaseIntegrationTest extends AbstractIntegrationTest {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String API_KEY = "test-service-key";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void entriesWrittenByTheOwner_areRetrievedByTheAssistant_closestFirst() throws Exception {
        String phone = "+33100000201";
        String token = registerAndGetToken("kb-owner@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Savoir", phone);

        createEntry(token, restaurantId, "Allergènes", "Nous proposons des plats sans gluten et sans lactose sur demande.");
        createEntry(token, restaurantId, "Parking", "Le parking Jaurès est à deux minutes à pied.");
        createEntry(token, restaurantId, "Animaux", "Les chiens sont acceptés en terrasse uniquement.");

        mockMvc.perform(get("/api/calls/knowledge")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", phone)
                .param("question", "avez-vous des plats sans gluten ?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(3))
                .andExpect(jsonPath("$.matches[0].title").value("Allergènes"))
                .andExpect(jsonPath("$.matches[0].score").isNumber());

        // The restaurateur's "test the assistant" box returns the same ranking.
        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/knowledge/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"question":"où est le parking ?"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].title").value("Parking"));
    }

    @Test
    void updatingAnEntry_reEmbedsIt_andDeletingRemovesItFromSearch() throws Exception {
        String phone = "+33100000202";
        String token = registerAndGetToken("kb-edit@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Modif", phone);
        String entryId = createEntry(token, restaurantId, "Terrasse", "Vingt couverts en terrasse.");
        createEntry(token, restaurantId, "Horaires fêtes", "Fermé le 25 décembre.");

        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/knowledge/" + entryId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"Menu enfant","content":"Un menu enfant à neuf euros."}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Menu enfant"));

        mockMvc.perform(get("/api/calls/knowledge")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", phone)
                .param("question", "vous avez un menu enfant ?")
                .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(1))
                .andExpect(jsonPath("$.matches[0].id").value(entryId));

        mockMvc.perform(delete("/api/restaurants/" + restaurantId + "/knowledge/" + entryId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/restaurants/" + restaurantId + "/knowledge")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void unansweredQuestion_isCounted_answeredOnce_thenRetrievable() throws Exception {
        String phone = "+33100000203";
        String token = registerAndGetToken("kb-loop@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Boucle", phone);

        // Two callers ask the same thing, worded slightly differently.
        report(phone, "Vous avez un menu enfant ?");
        report(phone, "vous avez un menu enfant");
        report(phone, "On peut venir avec un chien ?");

        String open = mockMvc.perform(get("/api/restaurants/" + restaurantId + "/knowledge/questions")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Most asked first.
                .andExpect(jsonPath("$[0].question").value("Vous avez un menu enfant ?"))
                .andExpect(jsonPath("$[0].askedCount").value(2))
                .andReturn().getResponse().getContentAsString();
        String questionId = JsonPath.read(open, "$[0].id");
        String otherId = JsonPath.read(open, "$[1].id");

        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/knowledge/questions/" + questionId + "/answer")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"answer":"Oui, un menu enfant à neuf euros."}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("unanswered"))
                .andExpect(jsonPath("$.title").value("Vous avez un menu enfant ?"));

        // Answering twice is refused; ignoring removes the other from the list.
        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/knowledge/questions/" + questionId + "/answer")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"answer":"Encore."}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("question_already_handled"));
        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/knowledge/questions/" + otherId + "/ignore")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/restaurants/" + restaurantId + "/knowledge/questions")
                .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.length()").value(0));

        // The next caller now gets the restaurateur's answer.
        mockMvc.perform(get("/api/calls/knowledge")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", phone)
                .param("question", "est-ce que vous avez un menu enfant ?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].content").value("Oui, un menu enfant à neuf euros."));
    }

    @Test
    void knowledgeIsWalledPerRestaurant() throws Exception {
        String ownerToken = registerAndGetToken("kb-wall-a@example.com");
        String restaurantId = createOwnedRestaurant(ownerToken, "Chez A", "+33100000204");
        String entryId = createEntry(ownerToken, restaurantId, "Secret", "La recette de la maison.");

        String strangerToken = registerAndGetToken("kb-wall-b@example.com");
        String otherPhone = "+33100000205";
        String otherRestaurantId = createOwnedRestaurant(strangerToken, "Chez B", otherPhone);

        // Another organisation cannot list, write, or reach the entry through its own restaurant.
        mockMvc.perform(get("/api/restaurants/" + restaurantId + "/knowledge")
                .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/knowledge")
                .header("Authorization", "Bearer " + strangerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"x","content":"y"}"""))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/restaurants/" + otherRestaurantId + "/knowledge/" + entryId)
                .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());

        // And the assistant, calling for restaurant B, never sees restaurant A's entries.
        mockMvc.perform(get("/api/calls/knowledge")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", otherPhone)
                .param("question", "la recette de la maison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void assistantEndpoints_needTheServiceKey_notAUserToken() throws Exception {
        String token = registerAndGetToken("kb-auth@example.com");

        mockMvc.perform(get("/api/calls/knowledge")
                .param("restaurantPhone", "+33100000206").param("question", "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/calls/knowledge")
                .header("Authorization", "Bearer " + token)
                .param("restaurantPhone", "+33100000206").param("question", "x"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/calls/unanswered")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantPhone":"+33100000206","question":"x"}"""))
                .andExpect(status().isUnauthorized());
        // Unknown restaurant, valid key.
        mockMvc.perform(get("/api/calls/knowledge")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", "+33999999997").param("question", "x"))
                .andExpect(status().isNotFound());
    }

    @Test
    void entryContentIsValidated() throws Exception {
        String token = registerAndGetToken("kb-valid@example.com");
        String restaurantId = createOwnedRestaurant(token, "Chez Valide", "+33100000207");

        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/knowledge")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"","content":"%s"}""".formatted("a".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.content").exists());
    }

    private String createEntry(String token, String restaurantId, String title, String content) throws Exception {
        String created = mockMvc.perform(post("/api/restaurants/" + restaurantId + "/knowledge")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"title":"%s","content":"%s"}""".formatted(title, content)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private void report(String phone, String question) throws Exception {
        mockMvc.perform(post("/api/calls/unanswered")
                .header(API_KEY_HEADER, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantPhone":"%s","question":"%s"}""".formatted(phone, question)))
                .andExpect(status().isNoContent());
    }

    private String registerAndGetToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private String createOwnedRestaurant(String token, String name, String phone) throws Exception {
        String me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String organizationId = JsonPath.read(me, "$.organizationId");
        String created = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"%s","phoneNumber":"%s"}"""
                        .formatted(organizationId, name, phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }
}
