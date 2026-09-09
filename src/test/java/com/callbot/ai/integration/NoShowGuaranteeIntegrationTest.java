package com.callbot.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.ConnectWebhookEvent;
import com.callbot.ai.gateway.stripe.RegisteredCard;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.gateway.stripe.StripeConnectWebhookParser;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.service.NoShowPenaltyJob;
import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * The no-show guarantee end to end: a card registered without a charge, an absence
 * recorded by a person, and a debit that only happens once the window has closed.
 */
class NoShowGuaranteeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private NoShowPenaltyJob penaltyJob;
    @MockitoBean
    private StripeConnectGateway connect;
    @MockitoBean
    private StripeConnectWebhookParser webhookParser;

    private String token;
    private String restaurantId;
    private String account;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        account = "acct_" + suffix;
        token = registerAndGetToken("owner-noshow-" + suffix + "@example.com");
        UUID organizationId = organizationIdOf(token);

        String restaurant = mockMvc.perform(post("/api/restaurants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationId":"%s","name":"Chez No-Show","phoneNumber":"%s"}"""
                                .formatted(organizationId, uniquePhoneNumber())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        restaurantId = JsonPath.read(restaurant, "$.id");

        completeOnboarding();
        setNoShowMode(2500);
    }

    @Test
    void theDinerRegistersACardAndNothingIsDebited() throws Exception {
        String reservationId = createReservation("2030-07-01T19:00:00Z", "2030-07-01T21:00:00Z");
        String paymentToken = reservation(reservationId).getPaymentToken();

        mockMvc.perform(get("/api/public/reservations/paiement/" + paymentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guaranteeMode").value("no_show"))
                .andExpect(jsonPath("$.amountCents").value(10000));

        when(connect.createCardRegistration(any()))
                .thenReturn(new CheckoutSession("cs_setup", "https://checkout.stripe.com/cs_setup"));

        mockMvc.perform(post("/api/public/reservations/paiement/" + paymentToken + "/checkout"))
                .andExpect(status().isOk());

        // A card registration, never a payment: nothing is taken at booking time.
        verify(connect).createCardRegistration(any());
        verify(connect, never()).createBookingFeeCheckout(any());

        registerCardFor(reservationId);

        mockMvc.perform(get("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.guaranteeStatus").value("secured"));
    }

    @Test
    void anAbsenceIsRecordedByAPersonAndDebitedOnlyOnceTheWindowHasClosed() throws Exception {
        String reservationId = pastReservationWithACard();

        mockMvc.perform(post("/api/reservations/" + reservationId + "/no-show")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("no_show"));

        Reservation reservation = reservation(reservationId);
        assertThat(reservation.getNoShowRecordedBy()).isNotNull();
        assertThat(reservation.getPenaltyChargedAt()).isNull();

        // Inside the window, the sweep leaves the card alone.
        penaltyJob.chargeDuePenalties();
        verify(connect, never()).chargeNoShowPenalty(any());

        // Once it closes, and only then.
        reservation.setPenaltyDueAt(OffsetDateTime.now().minusMinutes(1));
        reservationRepository.save(reservation);
        when(connect.chargeNoShowPenalty(any())).thenReturn("pi_penalty");

        penaltyJob.chargeDuePenalties();

        Reservation charged = reservation(reservationId);
        assertThat(charged.getGuaranteeStatus()).isEqualTo("charged");
        assertThat(charged.getPenaltyChargedAt()).isNotNull();
        // No commission is taken on a penalty.
        assertThat(charged.getApplicationFeeCents()).isZero();
    }

    @Test
    void takingTheAbsenceBackWithinTheWindowMeansNobodyIsEverCharged() throws Exception {
        String reservationId = pastReservationWithACard();

        mockMvc.perform(post("/api/reservations/" + reservationId + "/no-show")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/reservations/" + reservationId + "/no-show")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));

        penaltyJob.chargeDuePenalties();

        verify(connect, never()).chargeNoShowPenalty(any());
        assertThat(reservation(reservationId).getPenaltyDueAt()).isNull();
    }

    @Test
    void aCardRefusedTwiceIsGivenUpOnRatherThanRetriedForever() throws Exception {
        String reservationId = pastReservationWithACard();
        mockMvc.perform(post("/api/reservations/" + reservationId + "/no-show")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        when(connect.chargeNoShowPenalty(any()))
                .thenThrow(new com.callbot.ai.exception.PaymentGatewayException("card_declined"));

        dueNow(reservationId);
        penaltyJob.chargeDuePenalties();
        assertThat(reservation(reservationId).getPenaltyAttempts()).isEqualTo(1);
        // Still owed, but not for another day.
        assertThat(reservation(reservationId).getPenaltyDueAt()).isAfter(OffsetDateTime.now());

        dueNow(reservationId);
        penaltyJob.chargeDuePenalties();

        Reservation abandoned = reservation(reservationId);
        assertThat(abandoned.getGuaranteeStatus()).isEqualTo("charge_failed");
        assertThat(abandoned.getPenaltyDueAt()).isNull();

        // A third sweep must not touch the card again.
        penaltyJob.chargeDuePenalties();
        verify(connect, org.mockito.Mockito.times(2)).chargeNoShowPenalty(any());
    }

    @Test
    void anotherOrganizationCannotRecordAnAbsenceOnYourReservation() throws Exception {
        String reservationId = pastReservationWithACard();
        String intruder = registerAndGetToken(
                "intruder-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");

        mockMvc.perform(post("/api/reservations/" + reservationId + "/no-show")
                        .header("Authorization", "Bearer " + intruder))
                .andExpect(status().isNotFound());
    }

    private void dueNow(String reservationId) {
        Reservation reservation = reservation(reservationId);
        reservation.setPenaltyDueAt(OffsetDateTime.now().minusMinutes(1));
        reservationRepository.save(reservation);
    }

    /** A reservation whose service is over and whose diner left a card. */
    private String pastReservationWithACard() throws Exception {
        String reservationId = createReservation("2030-07-02T19:00:00Z", "2030-07-02T21:00:00Z");
        registerCardFor(reservationId);

        Reservation reservation = reservation(reservationId);
        reservation.setStartsAt(OffsetDateTime.now().minusHours(3));
        reservation.setEndsAt(OffsetDateTime.now().minusHours(1));
        reservationRepository.save(reservation);
        return reservationId;
    }

    private void registerCardFor(String reservationId) throws Exception {
        when(connect.readRegisteredCard(anyString(), anyString()))
                .thenReturn(new RegisteredCard("cus_1", "pm_1"));
        when(webhookParser.parse(anyString(), any()))
                .thenReturn(Optional.of(new ConnectWebhookEvent.CardRegistered(
                        UUID.fromString(reservationId), "seti_1", account)));

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=whatever")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private String createReservation(String startsAt, String endsAt) throws Exception {
        String response = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","startsAt":"%s","endsAt":"%s","partySize":4}"""
                                .formatted(restaurantId, startsAt, endsAt)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private Reservation reservation(String reservationId) {
        return reservationRepository.findById(UUID.fromString(reservationId)).orElseThrow();
    }

    private void setNoShowMode(int centsPerGuest) throws Exception {
        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/guarantee-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"no_show","noShowPenaltyCentsPerGuest":%d}"""
                                .formatted(centsPerGuest)))
                .andExpect(status().isOk());
    }

    private void completeOnboarding() throws Exception {
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

    private static String uniquePhoneNumber() {
        return "+33" + String.format("%09d",
                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L));
    }

    private UUID organizationIdOf(String token) throws Exception {
        String me = mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
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
