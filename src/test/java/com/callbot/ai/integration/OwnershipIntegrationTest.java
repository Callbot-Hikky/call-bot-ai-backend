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

/**
 * Un utilisateur connecte ne voit et ne modifie que les donnees de son organisation :
 * un identifiant devine ne donne acces a rien, sur aucune ressource admin.
 */
class OwnershipIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anotherOrganizationCannotReadOrTouchMyData() throws Exception {
        String alice = registerAndGetToken("alice-owner@example.com");
        String bob = registerAndGetToken("bob-intruder@example.com");
        String restaurantId = createRestaurant(alice, "Chez Alice", "+33100000401");
        String tableId = created(alice, "/api/tables",
                "{\"restaurantId\":\"%s\",\"name\":\"T1\",\"capacity\":4}".formatted(restaurantId));
        String customerId = created(alice, "/api/customers",
                "{\"restaurantId\":\"%s\",\"phone\":\"0611111111\",\"firstName\":\"Nadia\"}".formatted(restaurantId));
        String reservationId = created(alice, "/api/reservations",
                ("{\"restaurantId\":\"%s\",\"customerId\":\"%s\",\"tableId\":\"%s\",\"startsAt\":\"2030-01-01T19:00:00Z\","
                        + "\"endsAt\":\"2030-01-01T21:00:00Z\",\"partySize\":2}").formatted(restaurantId, customerId, tableId));

        // Lecture : 403 partout pour Bob, 200 pour Alice.
        mockMvc.perform(get("/api/restaurants/" + restaurantId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tables/" + tableId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customers/" + customerId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reservations/" + reservationId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reservations?restaurantId=" + restaurantId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/floor-plans/" + restaurantId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reservations/" + reservationId).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk());

        // Ecriture : Bob ne peut ni modifier, ni supprimer, ni creer chez Alice.
        mockMvc.perform(put("/api/tables/" + tableId).header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"restaurantId\":\"%s\",\"name\":\"Hack\",\"capacity\":2}".formatted(restaurantId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/reservations/" + reservationId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/reservations").header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"restaurantId\":\"%s\",\"startsAt\":\"2030-01-02T19:00:00Z\","
                        + "\"endsAt\":\"2030-01-02T21:00:00Z\",\"partySize\":2}").formatted(restaurantId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/floor-plans/" + restaurantId).header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layout\":{}}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/restaurants/" + restaurantId).header("Authorization", "Bearer " + bob))
                .andExpect(status().isForbidden());

        // Les listes sont bornees a son organisation, quel que soit le parametre.
        mockMvc.perform(get("/api/restaurants?organizationId=" + organizationOf(alice)).header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/tables").header("Authorization", "Bearer " + bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Bob ne cree pas de restaurant dans l'organisation d'Alice.
        mockMvc.perform(post("/api/restaurants").header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"organizationId\":\"%s\",\"name\":\"Squat\",\"phoneNumber\":\"+33100000402\"}"
                        .formatted(organizationOf(alice))))
                .andExpect(status().isForbidden());

        // Le public, lui, reste public.
        mockMvc.perform(get("/api/public/restaurants/" + restaurantId + "/menu"))
                .andExpect(status().isOk());
    }

    private String created(String token, String url, String body) throws Exception {
        String response = mockMvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String createRestaurant(String token, String name, String phone) throws Exception {
        return created(token, "/api/restaurants",
                "{\"organizationId\":\"%s\",\"name\":\"%s\",\"phoneNumber\":\"%s\"}".formatted(organizationOf(token), name, phone));
    }

    private String registerAndGetToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private UUID organizationOf(String token) throws Exception {
        String me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(me, "$.organizationId"));
    }
}
