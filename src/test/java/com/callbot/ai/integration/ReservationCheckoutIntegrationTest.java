package com.callbot.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.stripe.BookingFeeCharge;
import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.ConnectWebhookEvent;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.gateway.stripe.StripeConnectWebhookParser;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * The money path end to end, from the restaurateur's onboarding to the diner's refund.
 *
 * <p>Stripe itself is mocked; everything else — routing, the security rules that let an
 * account-less diner in, the CHECK constraints, the frozen refund window — is real.
 */
class ReservationCheckoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReservationRepository reservationRepository;
    @MockitoBean
    private StripeConnectGateway connect;
    @MockitoBean
    private StripeConnectWebhookParser webhookParser;

    private String token;
    private String restaurantId;
    /** One Stripe account per test: the unique index on the column is real. */
    private String account;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        account = "acct_" + suffix;
        token = registerAndGetToken("owner-checkout-" + suffix + "@example.com");
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
    }

    @Test
    void aPayingModeIsRefusedUntilStripeHasClearedTheAccount() throws Exception {
        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/guarantee-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"booking_fee","bookingFeeCentsPerGuest":1500}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onboardingUnlocksThePayingModes() throws Exception {
        mockMvc.perform(get("/api/billing/connect").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.chargesEnabled").value(false));

        completeOnboarding();

        mockMvc.perform(get("/api/billing/connect").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.chargesEnabled").value(true));
    }

    @Test
    void aDinerPaysThroughTheirLinkAndTheReservationIsConfirmed() throws Exception {
        completeOnboarding();
        setBookingFeeMode(1500, 48);
        String reservationId = createReservation(6, "2030-06-01T19:00:00Z", "2030-06-01T21:00:00Z");
        String paymentToken = paymentTokenOf(reservationId);

        // The diner is not signed in, and never will be: the token is the whole credential.
        mockMvc.perform(get("/api/public/reservations/paiement/" + paymentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Chez Payant"))
                .andExpect(jsonPath("$.amountCents").value(9000))
                .andExpect(jsonPath("$.refundWindowHours").value(48))
                .andExpect(jsonPath("$.guaranteeStatus").value("awaiting"));

        when(connect.createBookingFeeCheckout(any()))
                .thenReturn(new CheckoutSession("cs_1", "https://checkout.stripe.com/cs_1"));

        mockMvc.perform(post("/api/public/reservations/paiement/" + paymentToken + "/checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://checkout.stripe.com/cs_1"));

        org.mockito.ArgumentCaptor<BookingFeeCharge> charge =
                org.mockito.ArgumentCaptor.forClass(BookingFeeCharge.class);
        verify(connect).createBookingFeeCheckout(charge.capture());
        assertThat(charge.getValue().applicationFeeCents()).isEqualTo(500);
        assertThat(charge.getValue().connectedAccountId()).isEqualTo(account);

        payFor(reservationId);

        mockMvc.perform(get("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.guaranteeStatus").value("secured"));

        // The payment link is single use.
        mockMvc.perform(get("/api/public/reservations/paiement/" + paymentToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancellingWellAheadOfTheServiceRefundsTheWholeFee() throws Exception {
        completeOnboarding();
        setBookingFeeMode(1500, 48);
        String reservationId = createReservation(2, "2030-06-02T19:00:00Z", "2030-06-02T21:00:00Z");
        payFor(reservationId);

        String cancellationToken = reservation(reservationId).getCancellationToken();

        mockMvc.perform(post("/api/public/reservations/annulation/" + cancellationToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true))
                .andExpect(jsonPath("$.refunded").value(true))
                .andExpect(jsonPath("$.refundedAmountCents").value(3000));

        verify(connect).refundFully(anyString());
        assertThat(reservation(reservationId).getGuaranteeStatus()).isEqualTo("refunded");
    }

    @Test
    void cancellingInsideThePromisedWindowKeepsTheFee() throws Exception {
        completeOnboarding();
        setBookingFeeMode(1500, 48);
        String reservationId = createReservation(2, "2030-06-03T19:00:00Z", "2030-06-03T21:00:00Z");
        payFor(reservationId);

        // The window frozen on the reservation is what counts — here, brought forward so
        // the service falls inside it without waiting three years for the clock.
        Reservation reservation = reservation(reservationId);
        reservation.setStartsAt(java.time.OffsetDateTime.now().plusHours(6));
        reservationRepository.save(reservation);

        mockMvc.perform(post("/api/public/reservations/annulation/" + reservation.getCancellationToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelled").value(true))
                .andExpect(jsonPath("$.refunded").value(false));

        verify(connect, never()).refundFully(anyString());
        assertThat(reservation(reservationId).getGuaranteeStatus()).isEqualTo("secured");
    }

    @Test
    void anUnknownLinkTellsTheDinerNothingAboutWhoElseHasBooked() throws Exception {
        mockMvc.perform(get("/api/public/reservations/paiement/whatever"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/public/reservations/annulation/whatever"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theLedgerIsScopedToTheCallersOwnOrganization() throws Exception {
        mockMvc.perform(get("/api/payouts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Drives the reservation through a Stripe webhook, signature verification mocked out. */
    private void payFor(String reservationId) throws Exception {
        when(webhookParser.parse(anyString(), any()))
                .thenReturn(Optional.of(new ConnectWebhookEvent.ReservationPaid(
                        UUID.fromString(reservationId), "cs_1", "pi_1", 0)));

        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=whatever")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private void completeOnboarding() throws Exception {
        when(connect.createConnectedAccount(anyString(), anyString())).thenReturn(account);
        when(connect.createOnboardingLink(account)).thenReturn("https://connect.stripe.com/setup/1");
        when(connect.fetchStatus(account)).thenReturn(new ConnectAccountStatus(account, true, true, true));

        mockMvc.perform(post("/api/billing/connect/onboarding")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://connect.stripe.com/setup/1"));

        mockMvc.perform(post("/api/billing/connect/refresh")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargesEnabled").value(true));
    }

    private void setBookingFeeMode(int centsPerGuest, int refundWindowHours) throws Exception {
        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/guarantee-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"booking_fee","bookingFeeCentsPerGuest":%d,"refundWindowHours":%d}"""
                                .formatted(centsPerGuest, refundWindowHours)))
                .andExpect(status().isOk());
    }

    private String createReservation(int partySize, String startsAt, String endsAt) throws Exception {
        String response = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","startsAt":"%s","endsAt":"%s","partySize":%d}"""
                                .formatted(restaurantId, startsAt, endsAt, partySize)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("awaiting_payment"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** The token is never exposed by the API — it is the diner's credential, not data. */
    private String paymentTokenOf(String reservationId) {
        return reservation(reservationId).getPaymentToken();
    }

    private Reservation reservation(String reservationId) {
        return reservationRepository.findById(UUID.fromString(reservationId)).orElseThrow();
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
