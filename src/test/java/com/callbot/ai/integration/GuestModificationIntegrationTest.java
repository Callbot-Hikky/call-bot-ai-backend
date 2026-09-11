package com.callbot.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.ConnectWebhookEvent;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.gateway.stripe.StripeConnectWebhookParser;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * A diner changing their own booking, holding nothing but the link they were sent.
 *
 * <p>Everything but Stripe is real: the migration that added the token and its unique
 * index, the security rule that lets an account-less caller through, the slot search, and
 * the register that records what went back. All of that is schema and routing behaviour a
 * mock would hide.
 */
class GuestModificationIntegrationTest extends AbstractIntegrationTest {

    /** Far enough out that the modification window is never the reason a test fails. */
    private static final String SLOT_START = "2030-07-01T19:00:00Z";
    private static final String SLOT_END = "2030-07-01T21:00:00Z";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationChargeRepository charges;
    @MockitoBean
    private StripeConnectGateway connect;
    @MockitoBean
    private StripeConnectWebhookParser webhookParser;

    private String token;
    private String restaurantId;
    private String stripeSuffix;
    private String bigTableId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        stripeSuffix = suffix;
        token = registerAndGetToken("owner-modif-" + suffix + "@example.com");
        UUID organizationId = organizationIdOf(token);

        String restaurant = mockMvc.perform(post("/api/restaurants")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationId":"%s","name":"Chez Modif","phoneNumber":"%s"}"""
                                .formatted(organizationId, uniquePhoneNumber())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        restaurantId = JsonPath.read(restaurant, "$.id");

        completeOnboarding();
        createTable("T1", 2);
        bigTableId = createTable("T2", 8);
    }

    // --- The link ------------------------------------------------------------

    @Test
    void everyReservationIsBornWithAModificationLink() throws Exception {
        freeMode();
        Reservation reservation = reservation(reservationFor(2));

        assertThat(reservation.getModificationToken()).isNotBlank();
        assertThat(reservation.getModificationToken())
                .isNotEqualTo(reservation.getCancellationToken());
    }

    @Test
    void theLinkNeedsNoAccount() throws Exception {
        freeMode();
        String reservationId = reservationFor(2);

        mockMvc.perform(get("/api/public/reservations/modifier/" + modificationToken(reservationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Chez Modif"))
                .andExpect(jsonPath("$.partySize").value(2))
                .andExpect(jsonPath("$.open").value(true));
    }

    @Test
    void anUnknownLinkRevealsNothing() throws Exception {
        mockMvc.perform(get("/api/public/reservations/modifier/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aLinkThatOutlivedItsBookingHandsBackNothingButWhoToCall() throws Exception {
        freeMode();
        String reservationId = reservationFor(2);
        String link = modificationToken(reservationId);
        mockMvc.perform(delete("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/public/reservations/modifier/" + link))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Chez Modif"))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.startsAt").doesNotExist())
                .andExpect(jsonPath("$.partySize").doesNotExist());
    }

    @Test
    void theLinkSurvivesBeingUsed() throws Exception {
        freeMode();
        String reservationId = reservationFor(4);
        String link = modificationToken(reservationId);

        mockMvc.perform(modify(link, """
                {"partySize":3}""")).andExpect(status().isOk());
        mockMvc.perform(modify(link, """
                {"partySize":2}""")).andExpect(status().isOk());

        assertThat(reservation(reservationId).getPartySize()).isEqualTo(2);
        assertThat(reservation(reservationId).getModificationToken()).isEqualTo(link);
    }

    // --- Covers and hours ----------------------------------------------------

    @Test
    void theSlotsOfferedFollowThePartyBeingConsidered() throws Exception {
        freeMode();
        String link = modificationToken(reservationFor(2));

        // Two covers fit anywhere; nine fit nowhere, since the largest table seats eight.
        mockMvc.perform(get("/api/public/reservations/modifier/" + link + "/creneaux")
                        .param("partySize", "2")
                        .param("fromDate", "2030-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].slots").isNotEmpty());

        mockMvc.perform(get("/api/public/reservations/modifier/" + link + "/creneaux")
                        .param("partySize", "9")
                        .param("fromDate", "2030-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].slots").isEmpty());
    }

    @Test
    void movingTheHourKeepsTheSittingLength() throws Exception {
        freeMode();
        String reservationId = reservationFor(2);

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"startsAt":"2030-07-01T20:00:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("2030-07-01T20:00:00Z"));

        assertThat(reservation(reservationId).getEndsAt())
                .isEqualTo(OffsetDateTime.parse("2030-07-01T22:00:00Z"));
    }

    @Test
    void anHourWithNoTableFreeIsRefused() throws Exception {
        freeMode();
        String reservationId = reservationFor(8);
        // The only table for eight is taken at nine, by somebody else.
        occupyBigTableAt("2030-07-02T21:00:00Z", "2030-07-02T23:00:00Z");

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"startsAt":"2030-07-02T21:00:00Z"}"""))
                .andExpect(status().isBadRequest());

        assertThat(reservation(reservationId).getStartsAt())
                .isEqualTo(OffsetDateTime.parse(SLOT_START));
    }

    @Test
    void aRiseWithNoTableLargeEnoughIsRefused() throws Exception {
        freeMode();
        String reservationId = reservationFor(2);

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"partySize":12}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_table_available"));
    }

    // --- The window the restaurateur set -------------------------------------

    @Test
    void pastTheRestaurateursWindowTheLinkOpensOntoNothing() throws Exception {
        // The restaurateur closes changes three hours out, and this table was sold under
        // that rule — the window has to be in place before the reservation is taken.
        setModificationWindow(3);
        String reservationId = reservationFor(2);
        // The service is now two hours away: inside the closed window.
        bringForwardTo(reservationId, OffsetDateTime.now().plusHours(2));

        mockMvc.perform(get("/api/public/reservations/modifier/" + modificationToken(reservationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false));

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"partySize":1}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theWindowIsFrozenWhenTheReservationIsTaken() throws Exception {
        freeMode();
        setModificationWindow(3);
        String reservationId = reservationFor(2);

        // The restaurateur tightens it afterwards; this diner keeps what they were sold.
        setModificationWindow(48);

        assertThat(reservation(reservationId).getModificationWindowHours()).isEqualTo(3);
    }

    // --- Money ---------------------------------------------------------------

    @Test
    void aFallingPartyGetsTheCoversItGaveUpBack() throws Exception {
        bookingFeeMode(1500);
        String reservationId = paidReservationFor(4);

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"partySize":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(2))
                // Two covers at 15,00 €.
                .andExpect(jsonPath("$.refundedAmountCents").value(3000));

        verify(connect).refundPartially(eq("pi_fee_" + stripeSuffix), eq(3000), anyString());

        // The charge stays payable: what is left was still sold.
        ReservationCharge fee = feeOf(reservationId);
        assertThat(fee.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(fee.getRefundedAmountCents()).isEqualTo(3000);
        assertThat(fee.getPayoutEligibleAt()).isNotNull();
        // 60,00 € less 3,50 € commission, of which half came back with the covers.
        assertThat(fee.restaurateurShareCents()).isEqualTo(2825);
    }

    @Test
    void aFallingPartyOnAFreeReservationMovesNoMoney() throws Exception {
        freeMode();
        String reservationId = reservationFor(4);

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"partySize":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmountCents").value(0));

        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }

    @Test
    void aRiseOnAPaidReservationAsksForTheDifferenceFirst() throws Exception {
        bookingFeeMode(1500);
        String reservationId = paidReservationFor(2);

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"partySize":5}"""))
                .andExpect(status().isOk())
                // Nothing is held: the party stays as sold until the money is in.
                .andExpect(jsonPath("$.partySize").value(2))
                .andExpect(jsonPath("$.pendingTopUp.targetPartySize").value(5))
                .andExpect(jsonPath("$.pendingTopUp.amountCents").value(4500))
                // The link that settles it, so the diner is walked there rather than told
                // to wait for a message.
                .andExpect(jsonPath("$.topUpPaymentToken").isNotEmpty());

        assertThat(reservation(reservationId).getPartySize()).isEqualTo(2);
    }

    @Test
    void aFallOnAnUnpaidReservationIsRepricedRatherThanRefunded() throws Exception {
        bookingFeeMode(1500);
        // Awaiting payment: the link was sent for four and has not been followed.
        String reservationId = reservationFor(4);

        mockMvc.perform(modify(modificationToken(reservationId), """
                {"partySize":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmountCents").value(0));

        assertThat(reservation(reservationId).getGuaranteeAmountCents()).isEqualTo(3000);
        verify(connect, never()).refundPartially(anyString(), anyInt(), anyString());
    }

    // --- helpers -------------------------------------------------------------

    private RequestBuilder modify(String link, String body) {
        return put("/api/public/reservations/modifier/" + link)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String modificationToken(String reservationId) {
        return reservation(reservationId).getModificationToken();
    }

    private Reservation reservation(String reservationId) {
        return reservationRepository.findById(UUID.fromString(reservationId)).orElseThrow();
    }

    private ReservationCharge feeOf(String reservationId) {
        List<ReservationCharge> rows = charges.findByReservationId(UUID.fromString(reservationId))
                .stream().filter(ReservationCharge::isBookingFee).toList();
        return rows.get(0);
    }

    /** Winds the service forward rather than waiting for the clock. */
    private void bringForwardTo(String reservationId, OffsetDateTime startsAt) {
        Reservation reservation = reservation(reservationId);
        reservation.setStartsAt(startsAt);
        reservation.setEndsAt(startsAt.plusHours(2));
        reservationRepository.save(reservation);
    }

    private void occupyBigTableAt(String startsAt, String endsAt) throws Exception {
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","tableId":"%s","startsAt":"%s",\
                                "endsAt":"%s","partySize":8}"""
                                .formatted(restaurantId, bigTableId, startsAt, endsAt)))
                .andExpect(status().isCreated());
    }

    private String reservationFor(int partySize) throws Exception {
        String response = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","startsAt":"%s","endsAt":"%s","partySize":%d}"""
                                .formatted(restaurantId, SLOT_START, SLOT_END, partySize)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** A reservation whose booking fee actually reached the register. */
    private String paidReservationFor(int partySize) throws Exception {
        String reservationId = reservationFor(partySize);
        when(webhookParser.parse(anyString(), any())).thenReturn(Optional.of(
                new ConnectWebhookEvent.ReservationPaid(UUID.fromString(reservationId),
                        "cs_fee_" + stripeSuffix, "pi_fee_" + stripeSuffix, 0)));
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=whatever")
                        .content("{}"))
                .andExpect(status().isOk());
        return reservationId;
    }

    private void freeMode() throws Exception {
        guaranteeSettings("""
                {"mode":"none"}""");
    }

    private void bookingFeeMode(int centsPerGuest) throws Exception {
        guaranteeSettings("""
                {"mode":"booking_fee","bookingFeeCentsPerGuest":%d,"refundWindowHours":24}"""
                .formatted(centsPerGuest));
    }

    private void setModificationWindow(int hours) throws Exception {
        guaranteeSettings("""
                {"mode":"none","modificationWindowHours":%d}""".formatted(hours));
    }

    private void guaranteeSettings(String body) throws Exception {
        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/guarantee-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String createTable(String name, int capacity) throws Exception {
        String response = mockMvc.perform(post("/api/tables")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","name":"%s","capacity":%d}"""
                                .formatted(restaurantId, name, capacity)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void completeOnboarding() throws Exception {
        String account = "acct_" + stripeSuffix;
        when(connect.createConnectedAccount(anyString(), anyString())).thenReturn(account);
        when(connect.createOnboardingLink(account)).thenReturn("https://connect.stripe.com/setup/1");
        when(connect.fetchStatus(account)).thenReturn(new ConnectAccountStatus(account, true, true, true));

        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/payment-account/onboarding")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/restaurants/" + restaurantId + "/payment-account/refresh")
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
