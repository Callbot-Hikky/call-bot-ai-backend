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
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class RestaurantCrudIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * The bug this guards against: register, never pay, log back in later, and still be
     * able to configure a restaurant. {@code organizationIdOf} deliberately isn't used
     * here, since it activates a subscription as a test convenience for every other test.
     */
    @Test
    void create_withoutActiveSubscription_returns402() throws Exception {
        String token = registerAndGetToken("owner-unpaid@example.com");
        String me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String organizationId = JsonPath.read(me, "$.organizationId");

        mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Chez Test","phoneNumber":"+33100000099"}"""
                        .formatted(organizationId)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error").value("subscription_required"));
    }

    @Test
    void restaurantCrudLifecycle() throws Exception {
        String token = registerAndGetToken("owner-crud@example.com");
        UUID organizationId = organizationIdOf(token);

        String created = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Chez Test","phoneNumber":"+33100000001"}"""
                        .formatted(organizationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Chez Test"))
                .andExpect(jsonPath("$.timezone").value("Europe/Paris"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(get("/api/restaurants/" + id)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+33100000001"));

        mockMvc.perform(put("/api/restaurants/" + id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Chez Test Renamed","phoneNumber":"+33100000001","city":"Paris"}"""
                        .formatted(organizationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Chez Test Renamed"))
                .andExpect(jsonPath("$.city").value("Paris"));

        mockMvc.perform(delete("/api/restaurants/" + id)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/restaurants/" + id)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void overlappingReservationsOnSameTableAreRejected() throws Exception {
        String token = registerAndGetToken("owner-overlap@example.com");
        UUID organizationId = organizationIdOf(token);

        String restaurant = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Overlap Resto","phoneNumber":"+33100000002"}"""
                        .formatted(organizationId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String restaurantId = JsonPath.read(restaurant, "$.id");

        String table = mockMvc.perform(post("/api/tables")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","name":"T1","capacity":4}""".formatted(restaurantId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tableId = JsonPath.read(table, "$.id");

        String firstReservation = """
                {"restaurantId":"%s","tableId":"%s","startsAt":"2030-01-01T19:00:00Z",
                 "endsAt":"2030-01-01T21:00:00Z","partySize":2}"""
                .formatted(restaurantId, tableId);
        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(firstReservation))
                .andExpect(status().isCreated());

        // Same table, overlapping time range -> blocked by the EXCLUDE constraint.
        String overlapping = """
                {"restaurantId":"%s","tableId":"%s","startsAt":"2030-01-01T20:00:00Z",
                 "endsAt":"2030-01-01T22:00:00Z","partySize":2}"""
                .formatted(restaurantId, tableId);
        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(overlapping))
                .andExpect(status().isConflict());
    }

    @Test
    void reservationExpandsTableAndCustomerOnDemand() throws Exception {
        String token = registerAndGetToken("owner-expand@example.com");
        UUID organizationId = organizationIdOf(token);

        String restaurant = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Expand Resto","phoneNumber":"+33100000003"}"""
                        .formatted(organizationId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String restaurantId = JsonPath.read(restaurant, "$.id");

        String table = mockMvc.perform(post("/api/tables")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","name":"T7","capacity":4}""".formatted(restaurantId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tableId = JsonPath.read(table, "$.id");

        String customer = mockMvc.perform(post("/api/customers")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","phone":"+33600000000","firstName":"Alice"}"""
                        .formatted(restaurantId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String customerId = JsonPath.read(customer, "$.id");

        String reservation = mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","tableId":"%s","customerId":"%s",
                         "startsAt":"2030-02-01T19:00:00Z","endsAt":"2030-02-01T21:00:00Z","partySize":2}"""
                        .formatted(restaurantId, tableId, customerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(reservation, "$.id");

        // Without expand: only ids, no nested objects.
        mockMvc.perform(get("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableId").value(tableId))
                .andExpect(jsonPath("$.table").doesNotExist())
                .andExpect(jsonPath("$.customer").doesNotExist());

        // With expand: the related objects are nested in the response.
        mockMvc.perform(get("/api/reservations/" + reservationId + "?expand=table,customer")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.table.id").value(tableId))
                .andExpect(jsonPath("$.table.name").value("T7"))
                .andExpect(jsonPath("$.customer.id").value(customerId))
                .andExpect(jsonPath("$.customer.firstName").value("Alice"));
    }

    /** The organization the freshly registered user owns — the only one they may write to. */
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

    /** L'organisation creee a l'inscription : la seule sur laquelle l'utilisateur peut agir. */
}
