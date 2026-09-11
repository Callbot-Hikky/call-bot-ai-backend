package com.callbot.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

class PublicBookingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    void aVisitorSeesSlotsBooksAndReadsBackWithoutAnySession() throws Exception {
        String restaurantId = restaurantWithOneTableOfFour("owner-booking@example.com", "+33100000301");

        // Creneaux : sans session, 7 jours, un creneau demain (restaurant sans horaires = ouvert 11h-23h).
        String slots = mockMvc.perform(get("/api/public/restaurants/" + restaurantId + "/slots?partySize=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(7))
                .andReturn().getResponse().getContentAsString();
        String startsAt = JsonPath.read(slots, "$.days[1].slots[0].startsAt");

        // Reservation : 201, source web, prenom repris, jamais le telephone.
        String created = mockMvc.perform(post("/api/public/restaurants/" + restaurantId + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsAt":"%s","partySize":2,
                         "customer":{"firstName":"Nadia","phone":"06 12 34 56 78"},"notes":"Terrasse"}"""
                        .formatted(startsAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.customerFirstName").value("Nadia"))
                .andExpect(jsonPath("$.restaurantName").value("Chez Booking"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(created, "$.id");

        // Lecture publique de sa propre reservation, sans session.
        mockMvc.perform(get("/api/public/reservations/" + reservationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(2))
                .andExpect(jsonPath("$.customerFirstName").value("Nadia"));

        // Le meme creneau une seconde fois : la seule table est prise, 409 avec un code stable.
        mockMvc.perform(post("/api/public/restaurants/" + restaurantId + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsAt":"%s","partySize":2,"customer":{"firstName":"Karim","phone":"0698765432"}}"""
                        .formatted(startsAt)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_table"));

        // Le creneau pris n'est plus propose.
        String after = mockMvc.perform(get("/api/public/restaurants/" + restaurantId + "/slots?partySize=2"))
                .andReturn().getResponse().getContentAsString();
        assertThat(after).doesNotContain(startsAt);
    }

    @Test
    void guardsStayInPlace() throws Exception {
        String restaurantId = restaurantWithOneTableOfFour("owner-guards@example.com", "+33100000302");

        mockMvc.perform(post("/api/public/restaurants/" + restaurantId + "/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"startsAt":"2030-01-01T19:30:00Z","partySize":16,
                         "customer":{"firstName":"Nadia","phone":"0612345678"}}"""))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/public/reservations/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());

        // Rien d'autre sous /api/public n'est ouvert.
        mockMvc.perform(get("/api/public/restaurants/" + restaurantId + "/anything"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reservations"))
                .andExpect(status().isUnauthorized());
    }

    private String restaurantWithOneTableOfFour(String email, String phone) throws Exception {
        String register = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"password123"}""".formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(register, "$.accessToken");
        UUID organizationId = organizationRepository.save(
                Organization.builder().name("Booking Org " + email).build()).getId();
        String restaurant = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Chez Booking","phoneNumber":"%s"}"""
                        .formatted(organizationId, phone)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String restaurantId = JsonPath.read(restaurant, "$.id");
        mockMvc.perform(post("/api/tables")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","name":"T1","capacity":4}""".formatted(restaurantId)))
                .andExpect(status().isCreated());
        return restaurantId;
    }
}
