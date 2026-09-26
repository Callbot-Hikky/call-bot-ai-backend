package com.callbot.ai.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                    "startsAt": "%s",
                    "endsAt": "%s",
                    "partySize": 2
                  }
                }""".formatted(sid, restaurantPhone, tomorrowAt(19), tomorrowAt(21));

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
    void ingest_slotInThePast_is400_andCreatesNothing() throws Exception {
        String restaurantPhone = "+33100000053";
        seedRestaurant("owner-ingest-past@example.com", restaurantPhone);

        mockMvc.perform(post("/api/calls/ingest")
                .header(ServiceApiKeyHeader.NAME, API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"twilioCallSid":"CA-%s","restaurantPhone":"%s",
                         "customer":{"phone":"+33600000099"},
                         "reservation":{"startsAt":"2020-01-01T19:00:00Z",
                                        "endsAt":"2020-01-01T21:00:00Z","partySize":2}}"""
                        .formatted(UUID.randomUUID(), restaurantPhone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("slot_in_past"));
    }

    @Test
    void ingest_withoutApiKey_isRejected() throws Exception {
        mockMvc.perform(post("/api/calls/ingest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"twilioCallSid":"CA-x","restaurantPhone":"+33100000051",
                         "customer":{"phone":"+33600000000"},
                         "reservation":{"startsAt":"%s",
                                        "endsAt":"%s","partySize":2}}""".formatted(tomorrowAt(19), tomorrowAt(21))))
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
                         "reservation":{"startsAt":"%s",
                                        "endsAt":"%s","partySize":2}}"""
                        .formatted(UUID.randomUUID(), tomorrowAt(19), tomorrowAt(21))))
                .andExpect(status().isNotFound());
    }

    private void seedRestaurant(String ownerEmail, String phone) throws Exception {
        String token = registerAndGetToken(ownerEmail);
        // A restaurant belongs to the signed-in owner's own organization; creating one
        // under an unrelated organization is exactly what the scoping now refuses.
        UUID organizationId = organizationIdOf(token);
        mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Ingest Resto","phoneNumber":"%s"}"""
                        .formatted(organizationId, phone)))
                .andExpect(status().isCreated());
    }

    private UUID organizationIdOf(String token) throws Exception {
        String me = mockMvc.perform(get("/api/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JsonPath.read(me, "$.organizationId"));
        activateSubscription(organizationId);
        return organizationId;
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

    /** L'organisation creee a l'inscription : la seule sur laquelle l'utilisateur peut agir. */
    /** Tomorrow at the given UTC hour, ISO-8601: always inside the 7-day booking window. */
    private static String tomorrowAt(int hourUtc) {
        return java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1)
                .atTime(hourUtc, 0).atOffset(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
