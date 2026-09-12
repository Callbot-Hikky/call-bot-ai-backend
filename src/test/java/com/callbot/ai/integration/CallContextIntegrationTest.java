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
class CallContextIntegrationTest extends AbstractIntegrationTest {

    private static final String API_KEY_HEADER = "X-Api-Key";
    private static final String API_KEY = "test-service-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void context_returnsAttributesAndPolicies() throws Exception {
        String phone = "+33100000060";
        seedRestaurantWithTable(phone, "owner-context@example.com", 4);

        mockMvc.perform(get("/api/calls/context")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurant.phoneNumber").value(phone))
                // Free-form attributes survive the JSONB round-trip.
                .andExpect(jsonPath("$.attributes.halal").value(true))
                .andExpect(jsonPath("$.attributes.terrace").value(false))
                .andExpect(jsonPath("$.policies.maxPartySize").value(4))
                .andExpect(jsonPath("$.policies.defaultDurationMinutes").value(90));
    }

    @Test
    void availability_whenFree_thenTakenAfterReservation() throws Exception {
        String phone = "+33100000061";
        String token = seedRestaurantWithTable(phone, "owner-avail@example.com", 2);
        String slot = "2030-04-01T19:00:00Z";

        String free = mockMvc.perform(get("/api/calls/availability")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", phone)
                .param("startsAt", slot)
                .param("partySize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.tableId").exists())
                .andReturn().getResponse().getContentAsString();
        String tableId = JsonPath.read(free, "$.tableId");
        String restaurantId = JsonPath.read(mockMvc.perform(get("/api/calls/context")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", phone))
                .andReturn().getResponse().getContentAsString(), "$.restaurant.id");

        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","tableId":"%s","startsAt":"%s",
                         "endsAt":"2030-04-01T21:00:00Z","partySize":2}"""
                        .formatted(restaurantId, tableId, slot)))
                .andExpect(status().isCreated());

        // Same slot is now refused, with alternatives instead.
        mockMvc.perform(get("/api/calls/availability")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", phone)
                .param("startsAt", slot)
                .param("partySize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("no_table"))
                .andExpect(jsonPath("$.alternatives").isNotEmpty());
    }

    @Test
    void contextAndAvailability_withoutApiKey_areRejected() throws Exception {
        mockMvc.perform(get("/api/calls/context").param("restaurantPhone", "+33100000060"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/calls/availability")
                .param("restaurantPhone", "+33100000060")
                .param("startsAt", "2030-04-01T19:00:00Z")
                .param("partySize", "2"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void context_whenPhoneUnknown_returns404() throws Exception {
        mockMvc.perform(get("/api/calls/context")
                .header(API_KEY_HEADER, API_KEY)
                .param("restaurantPhone", "+33999999998"))
                .andExpect(status().isNotFound());
    }

    /** Creates an organization, a restaurant with attributes and one table. */
    private String seedRestaurantWithTable(String phone, String ownerEmail, int capacity) throws Exception {
        String token = registerAndGetToken(ownerEmail);
        UUID organizationId = organizationOf(token);

        String restaurant = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Context Resto","phoneNumber":"%s",
                         "attributes":{"halal":true,"terrace":false}}"""
                        .formatted(organizationId, phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String restaurantId = JsonPath.read(restaurant, "$.id");

        mockMvc.perform(post("/api/tables")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","name":"T1","capacity":%d}"""
                        .formatted(restaurantId, capacity)))
                .andExpect(status().isCreated());
        return token;
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

    /** L'organisation creee a l'inscription : la seule sur laquelle l'utilisateur peut agir. */
    private UUID organizationOf(String token) throws Exception {
        String me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(me, "$.organizationId"));
    }
}
