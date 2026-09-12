package com.callbot.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * Le cycle de vie d'une reservation cote restaurateur, sur un vrai Postgres :
 * modification, suppression, creneaux de replanification, liberation de la table
 * par une reservation terminee (V7), et double-reservation sur une table secondaire (V16).
 */
class ReservationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void updateDeleteAndRescheduleSlots() throws Exception {
        String token = registerAndGetToken("owner-resa-crud@example.com");
        String restaurantId = createRestaurant(token, "+33100000501");
        String tableId = created(token, "/api/tables", "{\"restaurantId\":\"%s\",\"name\":\"T1\",\"capacity\":4}".formatted(restaurantId));
        String reservationId = created(token, "/api/reservations", reservation(restaurantId, tableId, "2030-01-01T19:00:00Z", "2030-01-01T21:00:00Z"));

        // Deplacer d'une heure : le PUT complet est accepte, la lecture le reflete.
        mockMvc.perform(put("/api/reservations/" + reservationId + "?notify=true")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservation(restaurantId, tableId, "2030-01-01T20:00:00Z", "2030-01-01T22:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value(org.hamcrest.Matchers.startsWith("2030-01-01T20:00")));

        // Les creneaux de replanification : 7 jours, sans exclure sa propre reservation du calcul
        // (elle est ignoree, sinon elle bloquerait son propre creneau).
        mockMvc.perform(get("/api/reservations/" + reservationId + "/reschedule-slots?fromDate=2030-01-01")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(7))
                .andExpect(jsonPath("$.days[0].slots[?(@.startsAt =~ /2030-01-01T20:00.*/)]").exists());

        // Suppression : 204, puis 404.
        mockMvc.perform(delete("/api/reservations/" + reservationId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/reservations/" + reservationId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void aCompletedReservationFreesItsTable() throws Exception {
        String token = registerAndGetToken("owner-resa-v7@example.com");
        String restaurantId = createRestaurant(token, "+33100000502");
        String tableId = created(token, "/api/tables", "{\"restaurantId\":\"%s\",\"name\":\"T1\",\"capacity\":4}".formatted(restaurantId));
        String first = created(token, "/api/reservations", reservation(restaurantId, tableId, "2030-02-01T19:00:00Z", "2030-02-01T21:00:00Z"));

        // Meme table, meme heure, tant que la premiere est active : refuse.
        mockMvc.perform(post("/api/reservations").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservation(restaurantId, tableId, "2030-02-01T19:30:00Z", "2030-02-01T21:30:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("table_overlap"));

        // Terminee (partis plus tot) : la table est libre, la base comme le calcul de disponibilite.
        mockMvc.perform(put("/api/reservations/" + first).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservation(restaurantId, tableId, "2030-02-01T19:00:00Z", "2030-02-01T21:00:00Z", "completed")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/reservations").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservation(restaurantId, tableId, "2030-02-01T19:30:00Z", "2030-02-01T21:30:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void aSecondaryTableCannotBeBookedTwice() throws Exception {
        String token = registerAndGetToken("owner-resa-v16@example.com");
        String restaurantId = createRestaurant(token, "+33100000503");
        String t1 = created(token, "/api/tables", "{\"restaurantId\":\"%s\",\"name\":\"T1\",\"capacity\":4}".formatted(restaurantId));
        String t2 = created(token, "/api/tables", "{\"restaurantId\":\"%s\",\"name\":\"T2\",\"capacity\":4}".formatted(restaurantId));
        created(token, "/api/reservations", reservation(restaurantId, t1, "2030-03-01T19:00:00Z", "2030-03-01T21:00:00Z"));
        String group = created(token, "/api/reservations", reservation(restaurantId, t2, "2030-03-01T19:00:00Z", "2030-03-01T21:00:00Z"));

        // La reservation « groupe » sur T2 s'etend a T1 (table secondaire, deja prise) :
        // ni EXCLUDE (table principale seulement) ni l'applicatif, c'est le trigger V16 qui refuse.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO reservation_tables (reservation_id, table_id) VALUES (?::uuid, ?::uuid)", group, t1))
                .hasMessageContaining("no_overlapping_reservation");
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation_tables WHERE reservation_id = ?::uuid", Integer.class, group);
        assertThat(rows).isEqualTo(1);
    }

    private static String reservation(String restaurantId, String tableId, String startsAt, String endsAt) {
        return reservation(restaurantId, tableId, startsAt, endsAt, null);
    }

    private static String reservation(String restaurantId, String tableId, String startsAt, String endsAt, String status) {
        String statusField = status == null ? "" : ",\"status\":\"" + status + "\"";
        return "{\"restaurantId\":\"%s\",\"tableId\":\"%s\",\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"partySize\":2%s}"
                .formatted(restaurantId, tableId, startsAt, endsAt, statusField);
    }

    private String created(String token, String url, String body) throws Exception {
        String response = mockMvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String createRestaurant(String token, String phone) throws Exception {
        String me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        UUID organizationId = UUID.fromString(JsonPath.read(me, "$.organizationId"));
        return created(token, "/api/restaurants",
                "{\"organizationId\":\"%s\",\"name\":\"Chez Resa\",\"phoneNumber\":\"%s\"}".formatted(organizationId, phone));
    }

    private String registerAndGetToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
