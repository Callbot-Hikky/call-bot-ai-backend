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
 * Two restaurateurs who share nothing must see nothing of each other.
 *
 * <p>Everything is asserted over HTTP, because that is the surface an attacker has. The
 * expected answer to every crossing is <b>404</b>, never 403: a 403 would confirm that
 * the restaurant, diner or table someone guessed at is real.
 */
class OrganizationIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String mine;
    private String theirs;
    private String myRestaurant;
    private String theirRestaurant;
    private String theirCustomer;
    private String theirTable;
    private String theirHours;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        mine = registerAndGetToken("mine-" + suffix + "@example.com");
        theirs = registerAndGetToken("theirs-" + suffix + "@example.com");

        myRestaurant = createRestaurant(mine, "Chez Moi");
        theirRestaurant = createRestaurant(theirs, "Chez Eux");

        theirCustomer = create(theirs, "/api/customers", """
                {"restaurantId":"%s","phone":"%s","firstName":"Jean"}"""
                .formatted(theirRestaurant, uniquePhoneNumber()));
        theirTable = create(theirs, "/api/tables", """
                {"restaurantId":"%s","name":"T1","capacity":4}""".formatted(theirRestaurant));
        theirHours = create(theirs, "/api/restaurant-hours", """
                {"restaurantId":"%s","dayOfWeek":1,"service":"dinner",
                 "opensAt":"19:00","closesAt":"23:00"}""".formatted(theirRestaurant));
    }

    @Test
    void anotherOrganizationsRestaurantIsSimplyNotThere() throws Exception {
        mockMvc.perform(get("/api/restaurants/" + theirRestaurant).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/restaurants/" + theirRestaurant).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/restaurants/" + theirRestaurant)
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationId":"%s","name":"Détourné","phoneNumber":"%s"}"""
                                .formatted(UUID.randomUUID(), uniquePhoneNumber())))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingRestaurantsNeverWidensPastYourOwnOrganization() throws Exception {
        // Even asking explicitly for their organization returns yours, not theirs.
        String organizationId = JsonPath.read(me(theirs), "$.organizationId");

        mockMvc.perform(get("/api/restaurants")
                        .param("organizationId", organizationId)
                        .header("Authorization", "Bearer " + mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(myRestaurant));
    }

    @Test
    void anotherOrganizationsDinersAreOutOfReach() throws Exception {
        mockMvc.perform(get("/api/customers/" + theirCustomer).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/customers/" + theirCustomer).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/customers")
                        .param("restaurantId", theirRestaurant)
                        .header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        // Without a filter, only your own restaurants' diners come back.
        mockMvc.perform(get("/api/customers").header("Authorization", "Bearer " + mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anotherOrganizationsTablesAndHoursAreOutOfReach() throws Exception {
        mockMvc.perform(get("/api/tables/" + theirTable).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/tables/" + theirTable).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/restaurant-hours/" + theirHours).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/restaurant-hours")
                        .param("restaurantId", theirRestaurant)
                        .header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganizationsFloorPlanCannotBeReadOrOverwritten() throws Exception {
        mockMvc.perform(put("/api/floor-plans/" + theirRestaurant)
                        .header("Authorization", "Bearer " + theirs)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"layout":{"version":2,"geometry":{}}}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/floor-plans/" + theirRestaurant).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/floor-plans/" + theirRestaurant)
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"layout":{"version":2,"geometry":{}}}"""))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/floor-plans/" + theirRestaurant).header("Authorization", "Bearer " + mine))
                .andExpect(status().isNotFound());
    }

    @Test
    void nothingCanBeCreatedUnderAnotherOrganizationsRestaurant() throws Exception {
        mockMvc.perform(post("/api/customers")
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","phone":"%s","firstName":"Intrus"}"""
                                .formatted(theirRestaurant, uniquePhoneNumber())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/tables")
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","name":"Intruse","capacity":2}"""
                                .formatted(theirRestaurant)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRestaurantCannotBeCreatedUnderSomeoneElsesOrganization() throws Exception {
        String organizationId = JsonPath.read(me(theirs), "$.organizationId");

        mockMvc.perform(post("/api/restaurants")
                        .header("Authorization", "Bearer " + mine)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationId":"%s","name":"Cheval de Troie","phoneNumber":"%s"}"""
                                .formatted(organizationId, uniquePhoneNumber())))
                .andExpect(status().isNotFound());
    }

    private String createRestaurant(String token, String name) throws Exception {
        String organizationId = JsonPath.read(me(token), "$.organizationId");
        return create(token, "/api/restaurants", """
                {"organizationId":"%s","name":"%s","phoneNumber":"%s"}"""
                .formatted(organizationId, name, uniquePhoneNumber()));
    }

    private String create(String token, String path, String body) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private String me(String token) throws Exception {
        return mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String uniquePhoneNumber() {
        return "+33" + String.format("%09d",
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L));
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
