package com.callbot.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

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

import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.ConnectWebhookEvent;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.gateway.stripe.StripeConnectWebhookParser;
import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.repository.ReservationChargeRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.service.PartySizeTopUpExpiryJob;
import com.callbot.ai.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * A party that grows on a reservation already paid for.
 *
 * <p>Everything but Stripe is real here, and deliberately so: the partial unique index
 * that allows one pending top-up per reservation, the widened {@code kind} constraint,
 * and the fact that the diner reaches their top-up through a token and nothing else are
 * all schema and routing behaviour that a mock would hide.
 */
class PartySizeTopUpIntegrationTest extends AbstractIntegrationTest {

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
    @Autowired
    private PartySizeTopUpExpiryJob expiryJob;

    private String token;
    private String restaurantId;
    private String account;
    /** Stripe ids are unique in the schema, and the container keeps rows between tests. */
    private String stripeSuffix;
    /** The only table that can seat a party bigger than two. */
    private String bigTableId;
    /** Whoever is holding it, when a test needs it held. */
    private String occupierId;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        account = "acct_" + suffix;
        stripeSuffix = suffix;
        token = registerAndGetToken("owner-topup-" + suffix + "@example.com");
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

        completeOnboarding();
        setBookingFeeMode(1500, 24);
        // Two tables: one seats the party as sold, one is large enough for the rise.
        createTable("T1", 2);
        bigTableId = createTable("T2", 8);
    }

    @Test
    void aRiseOnAPaidReservationOpensATopUpWithoutMovingTheReservation() throws Exception {
        String reservationId = paidReservationFor(2);

        mockMvc.perform(raiseTo(reservationId, 5))
                .andExpect(status().isOk())
                // The reservation is what it was sold as until the difference is settled.
                .andExpect(jsonPath("$.partySize").value(2))
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.pendingTopUp.targetPartySize").value(5))
                // Three guests at the 15 € a head the reservation was frozen at.
                .andExpect(jsonPath("$.pendingTopUp.amountCents").value(4500))
                .andExpect(jsonPath("$.pendingTopUp.expiresAt").exists());

        assertThat(reservation(reservationId).getPartySize()).isEqualTo(2);
    }

    @Test
    void theTopUpIsPricedOffTheFrozenTariffNotTheCurrentOne() throws Exception {
        String reservationId = paidReservationFor(2);

        // The restaurateur doubles their fee after the table was sold.
        setBookingFeeMode(3000, 24);

        mockMvc.perform(raiseTo(reservationId, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTopUp.amountCents").value(3000));
    }

    @Test
    void theTopUpCarriesTheAlloquenceCommission() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        // 5 % of 4500 plus 50 cents, exactly as on the booking fee it extends.
        assertThat(topUpOf(reservationId).getApplicationFeeCents()).isEqualTo(275);
    }

    @Test
    void theDinerGetsALinkThatIsNotTheOneThatPaidTheFee() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        ReservationCharge topUp = topUpOf(reservationId);
        assertThat(topUp.getPaymentToken()).isNotBlank();
        assertThat(topUp.getTokenExpiresAt())
                .isAfter(OffsetDateTime.now().plusMinutes(25))
                .isBefore(OffsetDateTime.now().plusMinutes(35));

        mockMvc.perform(get("/api/public/reservations/complement/" + topUp.getPaymentToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantName").value("Chez Payant"))
                .andExpect(jsonPath("$.currentPartySize").value(2))
                .andExpect(jsonPath("$.targetPartySize").value(5))
                .andExpect(jsonPath("$.amountCents").value(4500))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void theTokenThatPaidTheFeeCannotSettleTheTopUp() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        // Nulled when the fee was paid, so nothing of the first payment survives to reuse.
        assertThat(reservation(reservationId).getPaymentToken()).isNull();
        mockMvc.perform(get("/api/public/reservations/complement/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void theLinkOpensACheckoutForTheDifferenceAlone() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());
        when(connect.createPartySizeTopUpCheckout(any()))
                .thenReturn(new CheckoutSession(topUpSession(), "https://stripe.test/cs_topup"));

        String topUpToken = topUpOf(reservationId).getPaymentToken();
        mockMvc.perform(post("/api/public/reservations/complement/" + topUpToken + "/checkout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://stripe.test/cs_topup"));

        assertThat(topUpOf(reservationId).getStripeSessionId()).isEqualTo(topUpSession());
    }

    @Test
    void aSecondRequestWhileOneIsPendingIsRefused() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        mockMvc.perform(raiseTo(reservationId, 6))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("top_up_pending"));

        // One row, and it is still the first request.
        assertThat(topUpsOf(reservationId)).hasSize(1);
        assertThat(topUpOf(reservationId).getTargetPartySize()).isEqualTo(5);
    }

    @Test
    void theDashboardSeesThePendingTopUpOnTheReservation() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        mockMvc.perform(get("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(2))
                .andExpect(jsonPath("$.pendingTopUp.amountCents").value(4500))
                .andExpect(jsonPath("$.pendingTopUp.targetPartySize").value(5))
                .andExpect(jsonPath("$.pendingTopUp.expiresAt").exists());
    }

    @Test
    void aReservationWithNoTopUpSaysNothingAboutOne() throws Exception {
        String reservationId = paidReservationFor(2);

        mockMvc.perform(get("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTopUp").doesNotExist());
    }

    @Test
    void aRiseWithNoTableLargeEnoughIsRefusedBeforeAnyMoneyIsAskedFor() throws Exception {
        String reservationId = paidReservationFor(2);

        mockMvc.perform(raiseTo(reservationId, 12))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_table_available"));

        assertThat(topUpsOf(reservationId)).isEmpty();
    }

    @Test
    void aFallInCoversStillPassesStraightThrough() throws Exception {
        String reservationId = paidReservationFor(2);

        mockMvc.perform(raiseTo(reservationId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(1))
                .andExpect(jsonPath("$.pendingTopUp").doesNotExist());
    }

    @Test
    void settlingTheTopUpBooksTheMoneyInTheRegister() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());
        when(connect.createPartySizeTopUpCheckout(any()))
                .thenReturn(new CheckoutSession(topUpSession(), "https://stripe.test/cs_topup"));
        mockMvc.perform(post("/api/public/reservations/complement/"
                        + topUpOf(reservationId).getPaymentToken() + "/checkout"))
                .andExpect(status().isOk());

        webhookSays(new ConnectWebhookEvent.ReservationPaid(
                UUID.fromString(reservationId), topUpSession(), "pi_topup_" + stripeSuffix, 4500));

        ReservationCharge topUp = topUpsOf(reservationId).get(0);
        assertThat(topUp.getStatus()).isEqualTo(ChargeStatus.PAID);
        assertThat(topUp.getStripePaymentIntentId()).isEqualTo("pi_topup_" + stripeSuffix);
        // Single use: the link is burnt with the payment.
        assertThat(topUp.getPaymentToken()).isNull();
        // The booking fee it sits beside is untouched by any of this.
        assertThat(charges.findByReservationIdAndKindAndStatus(
                UUID.fromString(reservationId), ChargeKind.BOOKING_FEE, ChargeStatus.PAID))
                .isPresent();
    }

    @Test
    void settlingWithATableFreeMovesThePartyOntoIt() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        settleTheTopUp(reservationId);

        Reservation reservation = reservation(reservationId);
        assertThat(reservation.getPartySize()).isEqualTo(5);
        assertThat(reservation.getTableId()).isEqualTo(UUID.fromString(bigTableId));
        assertThat(topUpOf(reservationId).getStatus()).isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void settlingWithNoTableLeftHandsTheMoneyBackAndLeavesTheBookingAlone() throws Exception {
        // The price of holding nothing for thirty minutes: the only table that could
        // seat five is taken while the diner is on the payment page.
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());
        UUID tableAsSold = reservation(reservationId).getTableId();
        occupyTheBigTable();

        settleTheTopUp(reservationId);

        ReservationCharge topUp = topUpOf(reservationId);
        assertThat(topUp.getStatus()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(topUp.getRefundedAmountCents()).isEqualTo(4500);
        // Nothing of it will ever leave for the restaurateur's bank.
        assertThat(topUp.getPayoutEligibleAt()).isNull();

        Reservation reservation = reservation(reservationId);
        assertThat(reservation.getPartySize()).isEqualTo(2);
        assertThat(reservation.getTableId()).isEqualTo(tableAsSold);
    }

    @Test
    void anUnsettledTopUpLapsesOnceItsWindowCloses() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());
        windowClosedOn(reservationId);

        expiryJob.lapseExpiredTopUps();

        assertThat(topUpOf(reservationId).getStatus()).isEqualTo(ChargeStatus.LAPSED);
        // The booking never moved, and still has not.
        Reservation reservation = reservation(reservationId);
        assertThat(reservation.getPartySize()).isEqualTo(2);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void aRiseIsPossibleAgainOnceTheFirstRequestHasLapsed() throws Exception {
        // The partial index tolerates one pending request per reservation. A request left
        // pending for ever would refuse every later rise, which is the real cost of not
        // closing them.
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());
        windowClosedOn(reservationId);
        expiryJob.lapseExpiredTopUps();

        mockMvc.perform(raiseTo(reservationId, 6))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTopUp.targetPartySize").value(6));

        assertThat(topUpsOf(reservationId)).hasSize(2);
    }

    @Test
    void aRiseIsPossibleAgainAfterAnImpasse() throws Exception {
        // The other half of the same promise: a request that was paid and handed back
        // must free the reservation exactly as an expired one does. The diner may well
        // ask again — a table can come free between the impasse and the second attempt.
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());
        occupyTheBigTable();
        settleTheTopUp(reservationId);
        assertThat(topUpOf(reservationId).getStatus()).isEqualTo(ChargeStatus.REFUNDED);

        mockMvc.perform(raiseTo(reservationId, 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_table_available"));

        freeTheBigTable();
        mockMvc.perform(raiseTo(reservationId, 5))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTopUp.targetPartySize").value(5));

        assertThat(topUpsOf(reservationId)).hasSize(2);
    }

    @Test
    void cancellingTheReservationEndsTheRequestRidingOnIt() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/reservations/" + reservationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(topUpOf(reservationId).getStatus()).isEqualTo(ChargeStatus.LAPSED);
    }

    @Test
    void loweringThePartyLeavesNoOrphanedRequestBehind() throws Exception {
        // The request priced three extra guests against a party of two. Take the party
        // to one and the live link asks for a difference nobody has requested.
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());

        mockMvc.perform(raiseTo(reservationId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partySize").value(1))
                .andExpect(jsonPath("$.pendingTopUp").doesNotExist());

        assertThat(topUpOf(reservationId).getStatus()).isEqualTo(ChargeStatus.LAPSED);
    }

    @Test
    void aLapsedLinkStillExplainsItselfRatherThanVanishing() throws Exception {
        String reservationId = paidReservationFor(2);
        mockMvc.perform(raiseTo(reservationId, 5)).andExpect(status().isOk());
        String lapsedLink = topUpOf(reservationId).getPaymentToken();
        windowClosedOn(reservationId);
        expiryJob.lapseExpiredTopUps();

        mockMvc.perform(get("/api/public/reservations/complement/" + lapsedLink))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"));

        // And it opens nothing.
        mockMvc.perform(post("/api/public/reservations/complement/" + lapsedLink + "/checkout"))
                .andExpect(status().isBadRequest());
    }

    // --- helpers ------------------------------------------------------------

    /** Walks the diner all the way through Stripe: checkout opened, then paid. */
    private void settleTheTopUp(String reservationId) throws Exception {
        when(connect.createPartySizeTopUpCheckout(any()))
                .thenReturn(new CheckoutSession(topUpSession(), "https://stripe.test/cs_topup"));
        mockMvc.perform(post("/api/public/reservations/complement/"
                        + topUpOf(reservationId).getPaymentToken() + "/checkout"))
                .andExpect(status().isOk());

        webhookSays(new ConnectWebhookEvent.ReservationPaid(
                UUID.fromString(reservationId), topUpSession(), "pi_topup_" + stripeSuffix, 4500));
    }

    /** Someone else takes the only table big enough, over the same slot. */
    private void occupyTheBigTable() throws Exception {
        occupierId = JsonPath.read(mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","tableId":"%s","startsAt":"2030-07-01T19:00:00Z",\
                                "endsAt":"2030-07-01T21:00:00Z","partySize":8}"""
                                .formatted(restaurantId, bigTableId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** Frees it again, so a second attempt at the same rise has somewhere to go. */
    private void freeTheBigTable() throws Exception {
        mockMvc.perform(delete("/api/reservations/" + occupierId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    /** Winds the deadline back rather than waiting half an hour for it. */
    private void windowClosedOn(String reservationId) {
        ReservationCharge topUp = topUpOf(reservationId);
        topUp.setTokenExpiresAt(OffsetDateTime.now().minusMinutes(1));
        charges.save(topUp);
    }

    private org.springframework.test.web.servlet.RequestBuilder raiseTo(String reservationId,
            int partySize) {
        return put("/api/reservations/" + reservationId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"restaurantId":"%s","startsAt":"2030-07-01T19:00:00Z",\
                        "endsAt":"2030-07-01T21:00:00Z","partySize":%d}"""
                        .formatted(restaurantId, partySize));
    }

    /** A reservation that was actually paid for: the only case where a top-up is owed. */
    private String paidReservationFor(int partySize) throws Exception {
        String response = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restaurantId":"%s","startsAt":"2030-07-01T19:00:00Z",\
                                "endsAt":"2030-07-01T21:00:00Z","partySize":%d}"""
                                .formatted(restaurantId, partySize)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("awaiting_payment"))
                .andReturn().getResponse().getContentAsString();
        String reservationId = JsonPath.read(response, "$.id");

        webhookSays(new ConnectWebhookEvent.ReservationPaid(
                UUID.fromString(reservationId), "cs_fee_" + stripeSuffix,
                "pi_fee_" + stripeSuffix, 0));
        return reservationId;
    }

    private String topUpSession() {
        return "cs_topup_" + stripeSuffix;
    }

    private void webhookSays(ConnectWebhookEvent event) throws Exception {
        when(webhookParser.parse(anyString(), any())).thenReturn(Optional.of(event));
        mockMvc.perform(post("/api/payments/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Stripe-Signature", "t=1,v1=whatever")
                        .content("{}"))
                .andExpect(status().isOk());
    }

    private List<ReservationCharge> topUpsOf(String reservationId) {
        return charges.findByReservationId(UUID.fromString(reservationId)).stream()
                .filter(ReservationCharge::isPartySizeTopUp)
                .toList();
    }

    private ReservationCharge topUpOf(String reservationId) {
        return topUpsOf(reservationId).get(0);
    }

    private Reservation reservation(String reservationId) {
        return reservationRepository.findById(UUID.fromString(reservationId)).orElseThrow();
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

    private void setBookingFeeMode(int centsPerGuest, int refundWindowHours) throws Exception {
        mockMvc.perform(put("/api/restaurants/" + restaurantId + "/guarantee-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"booking_fee","bookingFeeCentsPerGuest":%d,"refundWindowHours":%d}"""
                                .formatted(centsPerGuest, refundWindowHours)))
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
        UUID organizationId = UUID.fromString(JsonPath.read(me, "$.organizationId"));
        activateSubscription(organizationId);
        return organizationId;
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
