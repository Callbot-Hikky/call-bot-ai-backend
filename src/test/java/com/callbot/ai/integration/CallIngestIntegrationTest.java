package com.callbot.ai.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.model.Organization;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

@TestPropertySource(properties = "app.service.api-key=test-service-key")
class CallIngestIntegrationTest extends AbstractIntegrationTest {

    private static final String API_KEY = "test-service-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void ingest_createsCustomerCallAndReservation_andIsIdempotent() throws Exception {
        String restaurantPhone = "+33100000050";
        seedRestaurant("owner-ingest@example.com", restaurantPhone);

        String sid = "CA-" + UUID.randomUUID();
        String payload = """
                {
                  "twilioCallSid": "%s",
                  "restaurantPhone": "%s",
                  "fromNumber": "+33611112222",
                  "customer": {"phone": "+33611112222", "firstName": "Alice"},
                  "reservation": {
                    "startsAt": "2030-03-01T19:00:00Z",
                    "endsAt": "2030-03-01T21:00:00Z",
                    "partySize": 2
                  }
                }""".formatted(sid, restaurantPhone);

        // First call: creates everything, alreadyProcessed = false.
        String first = mockMvc.perform(post("/api/calls/ingest")
                .header(ServiceApiKeyHeader.NAME, API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyProcessed").value(false))
                .andExpect(jsonPath("$.callId").exists())
                .andExpect(jsonPath("$.customerId").exists())
                .andExpect(jsonPath("$.reservation.partySize").value(2))
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(first, "$.reservation.id");

        // Same SID replayed: idempotent, same reservation, nothing recreated.
        mockMvc.perform(post("/api/calls/ingest")
                .header(ServiceApiKeyHeader.NAME, API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.alreadyProcessed").value(true))
                .andExpect(jsonPath("$.reservation.id").value(reservationId));
    }

    @Test
    void ingest_whenTableTakenMeanwhile_returns409WithAlternatives() throws Exception {
        String restaurantPhone = "+33100000052";
        String tableId = seedRestaurantWithTable("owner-ingest-overlap@example.com", restaurantPhone, 4);

        String payload = """
                {
                  "twilioCallSid": "CA-%s",
                  "restaurantPhone": "%s",
                  "fromNumber": "%s",
                  "customer": {"phone": "%s"},
                  "reservation": {
                    "tableId": "%s",
                    "startsAt": "2030-03-02T19:00:00Z",
                    "endsAt": "2030-03-02T21:00:00Z",
                    "partySize": 2
                  }
                }""";

        // A first caller books the table for 19:00.
        mockMvc.perform(post("/api/calls/ingest")
                .header(ServiceApiKeyHeader.NAME, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.formatted(UUID.randomUUID(), restaurantPhone,
                        "+33622223333", "+33622223333", tableId)))
                .andExpect(status().isCreated());

        // A second call (different SID, different customer) targets the same
        // table and slot: the EXCLUDE constraint refuses it, and the API answers
        // 409 with later slots rather than a bare error.
        mockMvc.perform(post("/api/calls/ingest")
                .header(ServiceApiKeyHeader.NAME, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.formatted(UUID.randomUUID(), restaurantPhone,
                        "+33633334444", "+33633334444", tableId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_overlap"))
                .andExpect(jsonPath("$.alternatives").isNotEmpty())
                .andExpect(jsonPath("$.alternatives[0].startsAt").exists())
                .andExpect(jsonPath("$.alternatives[0].tableId").value(tableId));
    }

    @Test
    void ingest_withoutApiKey_isRejected() throws Exception {
        mockMvc.perform(post("/api/calls/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"twilioCallSid":"CA-x","restaurantPhone":"+33100000051",
                         "customer":{"phone":"+33600000000"},
                         "reservation":{"startsAt":"2030-03-01T19:00:00Z",
                                        "endsAt":"2030-03-01T21:00:00Z","partySize":2}}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingest_unknownRestaurantPhone_returns404() throws Exception {
        mockMvc.perform(post("/api/calls/ingest")
                .header(ServiceApiKeyHeader.NAME, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"twilioCallSid":"CA-%s","restaurantPhone":"+33999999999",
                         "customer":{"phone":"+33600000000"},
                         "reservation":{"startsAt":"2030-03-01T19:00:00Z",
                                        "endsAt":"2030-03-01T21:00:00Z","partySize":2}}"""
                        .formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    private void seedRestaurant(String ownerEmail, String phone) throws Exception {
        String token = registerAndGetToken(ownerEmail);
        UUID organizationId = organizationRepository.save(
                Organization.builder().name("Ingest Org").build()).getId();
        mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Ingest Resto","phoneNumber":"%s"}"""
                        .formatted(organizationId, phone)))
                .andExpect(status().isCreated());
    }

    /** Seeds a restaurant with a single table and returns that table's id. */
    private String seedRestaurantWithTable(String ownerEmail, String phone, int capacity) throws Exception {
        String token = registerAndGetToken(ownerEmail);
        UUID organizationId = organizationRepository.save(
                Organization.builder().name("Ingest Org").build()).getId();
        String restaurant = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Ingest Resto","phoneNumber":"%s"}"""
                        .formatted(organizationId, phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String restaurantId = JsonPath.read(restaurant, "$.id");

        String table = mockMvc.perform(post("/api/tables")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","name":"T1","capacity":%d}"""
                        .formatted(restaurantId, capacity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(table, "$.id");
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

    /** API key header name (mirrors ServiceApiKeyFilter, which is package-private). */
    private static final class ServiceApiKeyHeader {
        static final String NAME = "X-Api-Key";
    }
}
