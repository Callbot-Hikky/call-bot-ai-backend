package com.callbot.ai.integration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/** End-to-end behaviour of a restaurant that charges for its reservations. */
class PaidReservationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private StripeConnectGateway connect;

    private String token;
    private String restaurantId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        token = registerAndGetToken("owner-paid-" + suffix + "@example.com");
        UUID organizationId = organizationIdOf(token);

        String restaurant = mockMvc.perform(post("/api/restaurants")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"organizationId":"%s","name":"Chez Payant","phoneNumber":"%s"}"""
                        .formatted(organizationId, uniquePhoneNumber())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        restaurantId = JsonPath.read(restaurant, "$.id");
        completeOnboarding("acct_" + suffix);
    }

    @Test
    void aFreeRestaurantConfirmsReservationsImmediately() throws Exception {
        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservationFor(2, "2030-05-01T19:00:00Z", "2030-05-01T21:00:00Z", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.guaranteeMode").value("none"))
                .andExpect(jsonPath("$.guaranteeStatus").value("not_required"))
                .andExpect(jsonPath("$.guaranteeAmountCents").doesNotExist());
    }

    @Test
    void bookingFeeIsMultipliedByThePartySizeAndHoldsTheTable() throws Exception {
        setGuaranteeMode("""
                {"mode":"booking_fee","bookingFeeCentsPerGuest":1500,"refundWindowHours":24}""");

        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservationFor(6, "2030-05-02T19:00:00Z", "2030-05-02T21:00:00Z", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("awaiting_payment"))
                .andExpect(jsonPath("$.guaranteeStatus").value("awaiting"))
                .andExpect(jsonPath("$.guaranteeAmountCents").value(9000))
                .andExpect(jsonPath("$.currency").value("eur"))
                .andExpect(jsonPath("$.guaranteeExpiresAt").exists());
    }

    @Test
    void theNoShowModeAsksForNoMoneyUpFront() throws Exception {
        setGuaranteeMode("""
                {"mode":"no_show","noShowPenaltyCentsPerGuest":2500,"refundWindowHours":48}""");

        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservationFor(4, "2030-05-03T19:00:00Z", "2030-05-03T21:00:00Z", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.guaranteeMode").value("no_show"))
                .andExpect(jsonPath("$.guaranteeAmountCents").value(10000));
    }

    @Test
    void staffCanWaiveTheGuarantee() throws Exception {
        setGuaranteeMode("""
                {"mode":"booking_fee","bookingFeeCentsPerGuest":1500,"refundWindowHours":24}""");

        mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservationFor(2, "2030-05-04T19:00:00Z", "2030-05-04T21:00:00Z", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.guaranteeStatus").value("exempted"));
    }

    @Test
    void changingTheModeLeavesExistingReservationsAlone() throws Exception {
        setGuaranteeMode("""
                {"mode":"booking_fee","bookingFeeCentsPerGuest":1500,"refundWindowHours":24}""");

        String reservation = mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservationFor(2, "2030-05-05T19:00:00Z", "2030-05-05T21:00:00Z", false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(reservation, "$.id");

        setGuaranteeMode("""
                {"mode":"none"}""");

        mockMvc.perform(get("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guaranteeMode").value("booking_fee"))
                .andExpect(jsonPath("$.guaranteeAmountCents").value(3000));
    }

    @Test
    void deletingAReservationCancelsItInsteadOfErasingIt() throws Exception {
        setGuaranteeMode("""
                {"mode":"booking_fee","bookingFeeCentsPerGuest":1500,"refundWindowHours":24}""");

        String reservation = mockMvc.perform(post("/api/reservations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reservationFor(2, "2030-05-06T19:00:00Z", "2030-05-06T21:00:00Z", false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(reservation, "$.id");

        mockMvc.perform(delete("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // The row survives: it carries the trace of a payment.
        mockMvc.perform(get("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.cancelledAt").exists());
    }

    @Test
    void aPayingModeWithoutAnAmountIsRejected() throws Exception {
        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/guarantee-settings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"booking_fee"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settingsAreReadableBack() throws Exception {
        setGuaranteeMode("""
                {"mode":"booking_fee","bookingFeeCentsPerGuest":2000,"refundWindowHours":72}""");

        mockMvc.perform(get("/api/restaurants/" + restaurantId + "/guarantee-settings")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("booking_fee"))
                .andExpect(jsonPath("$.bookingFeeCentsPerGuest").value(2000))
                .andExpect(jsonPath("$.refundWindowHours").value(72));
    }

    /**
     * A paying mode is unavailable until Stripe has cleared the account, so every test
     * that sets one must go through onboarding first — as a real restaurateur does.
     */
    private void completeOnboarding(String account) throws Exception {
        when(connect.createConnectedAccount(anyString(), anyString())).thenReturn(account);
        when(connect.createOnboardingLink(account)).thenReturn("https://connect.stripe.com/setup/1");
        when(connect.fetchStatus(account)).thenReturn(new ConnectAccountStatus(account, true, true, true));

        mockMvc.perform(post("/api/billing/connect/onboarding")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/billing/connect/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void setGuaranteeMode(String body) throws Exception {
        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/guarantee-settings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk());
    }

    private String reservationFor(int partySize, String startsAt, String endsAt, boolean exempt) {
        return """
                {"restaurantId":"%s","startsAt":"%s","endsAt":"%s","partySize":%d,"exemptGuarantee":%b}"""
                .formatted(restaurantId, startsAt, endsAt, partySize, exempt);
    }

    private static String uniquePhoneNumber() {
        return "+33" + String.format("%09d",
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L));
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
