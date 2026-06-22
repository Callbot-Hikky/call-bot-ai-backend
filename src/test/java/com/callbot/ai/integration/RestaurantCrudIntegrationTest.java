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

import com.callbot.ai.model.Organization;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

class RestaurantCrudIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void restaurantCrudLifecycle() throws Exception {
        String token = registerAndGetToken("owner-crud@example.com");
        UUID organizationId = organizationRepository.save(
                Organization.builder().name("Test Org").build()).getId();

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
        UUID organizationId = organizationRepository.save(
                Organization.builder().name("Overlap Org").build()).getId();

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
