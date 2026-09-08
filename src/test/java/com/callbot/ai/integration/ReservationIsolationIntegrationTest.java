package com.callbot.ai.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * A restaurateur must never reach another organization's reservations. Reservations
 * carry payment data and can trigger card debits, so a leak here is not only a
 * confidentiality problem: it would let one restaurateur act on someone else's diners.
 *
 * <p>Cross-organization access is answered with 404 rather than 403 on purpose — a 403
 * would confirm that the reservation exists.
 */
class ReservationIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String ownerToken;
    private String intruderToken;
    private String reservationId;
    private String restaurantId;

    @BeforeEach
    void setUp() throws Exception {
        // Each test gets its own users and restaurant: emails and phone numbers are unique
        // in the schema, and the context (hence the database) is shared across tests.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ownerToken = registerAndGetToken("owner-isolation-" + suffix + "@example.com");
        intruderToken = registerAndGetToken("intruder-isolation-" + suffix + "@example.com");

        UUID organizationId = organizationIdOf(ownerToken);
        String restaurant = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Chez Isolation","phoneNumber":"%s"}"""
                        .formatted(organizationId, uniquePhoneNumber())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        restaurantId = JsonPath.read(restaurant, "$.id");

        String reservation = mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","startsAt":"2030-03-01T19:00:00Z",
                         "endsAt":"2030-03-01T21:00:00Z","partySize":4}""".formatted(restaurantId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        reservationId = JsonPath.read(reservation, "$.id");
    }

    @Test
    void ownerReadsTheirOwnReservation() throws Exception {
        mockMvc.perform(get("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(4));
    }

    @Test
    void intruderCannotReadAnotherOrganizationReservation() throws Exception {
        mockMvc.perform(get("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void intruderCannotUpdateAnotherOrganizationReservation() throws Exception {
        mockMvc.perform(put("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + intruderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","startsAt":"2030-03-01T19:00:00Z",
                         "endsAt":"2030-03-01T21:00:00Z","partySize":8,"status":"no_show"}"""
                        .formatted(restaurantId)))
                .andExpect(status().isNotFound());

        // The reservation is untouched.
        mockMvc.perform(get("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(4))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void intruderCannotDeleteAnotherOrganizationReservation() throws Exception {
        mockMvc.perform(delete("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void listingIsScopedToTheCallerOrganization() throws Exception {
        mockMvc.perform(get("/api/reservations")
                .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + reservationId + "')]").exists());

        mockMvc.perform(get("/api/reservations")
                .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + reservationId + "')]").doesNotExist());
    }

    @Test
    void filteringByAnotherOrganizationRestaurantIsRejected() throws Exception {
        mockMvc.perform(get("/api/reservations?restaurantId=" + restaurantId)
                .header("Authorization", "Bearer " + intruderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void intruderCannotCreateAReservationInAnotherOrganizationRestaurant() throws Exception {
        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + intruderToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","startsAt":"2030-04-01T19:00:00Z",
                         "endsAt":"2030-04-01T21:00:00Z","partySize":2}""".formatted(restaurantId)))
                .andExpect(status().isNotFound());
    }

    /** The schema enforces a unique phone number per restaurant. */
    private static String uniquePhoneNumber() {
        return "+33" + String.format("%09d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L));
    }

    private UUID organizationIdOf(String token) throws Exception {
        String me = mockMvc.perform(get("/api/me")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(me, "$.organizationId"));
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
}
