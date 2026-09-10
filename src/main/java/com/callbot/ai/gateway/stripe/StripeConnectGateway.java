package com.callbot.ai.gateway.stripe;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.CheckoutSession;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Customer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.SetupIntent;
import com.stripe.model.AccountLink;
import com.stripe.model.Payout;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * Everything the diner-payment flow asks of Stripe Connect.
 *
 * <p>Deliberately outside {@code PaymentGateway}: that port exists so the subscription
 * flow could move to another provider, and this one cannot follow it. Destination
 * charges, connected accounts and application fees have no equivalent elsewhere, and
 * pretending otherwise would give a false promise of portability. See
 * {@code docs/adr/0001}.
 *
 * <p>Accounts are created on a <em>manual</em> payout schedule. A destination charge
 * credits the connected account at once, but nothing leaves for the restaurateur's
 * bank until {@link #payOut} is called — which is how "reversement J+1 après le
 * service" is honoured without Alloquence ever holding the money itself.
 */
@Component
public class StripeConnectGateway {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectGateway.class);

    /** Ties a Stripe object back to the reservation it paid for. */
    public static final String RESERVATION_METADATA_KEY = "reservationId";

    /** Refund states that mean no money moved. "pending" and "succeeded" both did. */
    private static final Set<String> REFUND_FAILED = Set.of("failed", "canceled");

    private final StripeProperties stripe;
    private final StripeConnectProperties connect;
    private final StripeClient client;

    public StripeConnectGateway(StripeProperties stripe, StripeConnectProperties connect) {
        this.stripe = stripe;
        this.connect = connect;
        this.client = new StripeClient(stripe.secretKey() == null ? "" : stripe.secretKey());
    }

    /** Opens an Express account for a restaurateur who has never taken a payment. */
    public String createConnectedAccount(String email, String organizationName) {
        requireKey();
        AccountCreateParams params = AccountCreateParams.builder()
                .setType(AccountCreateParams.Type.EXPRESS)
                .setCountry(connect.country())
                .setEmail(email)
                .setBusinessProfile(AccountCreateParams.BusinessProfile.builder()
                        .setName(organizationName)
                        .build())
                .setCapabilities(AccountCreateParams.Capabilities.builder()
                        .setCardPayments(AccountCreateParams.Capabilities.CardPayments.builder()
                                .setRequested(true).build())
                        .setTransfers(AccountCreateParams.Capabilities.Transfers.builder()
                                .setRequested(true).build())
                        .build())
                .setSettings(AccountCreateParams.Settings.builder()
                        .setPayouts(AccountCreateParams.Settings.Payouts.builder()
                                .setSchedule(AccountCreateParams.Settings.Payouts.Schedule.builder()
                                        .setInterval(AccountCreateParams.Settings.Payouts.Schedule.Interval.MANUAL)
                                        .build())
                                .build())
                        .build())
                .build();
        try {
            Account account = client.accounts().create(params);
            return account.getId();
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to create the Stripe connected account", e);
        }
    }

    /** A single-use link to Stripe's hosted onboarding, valid a few minutes. */
    public String createOnboardingLink(String accountId) {
        requireKey();
        AccountLinkCreateParams params = AccountLinkCreateParams.builder()
                .setAccount(accountId)
                .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                .setRefreshUrl(connect.refreshUrl())
                .setReturnUrl(connect.returnUrl())
                .build();
        try {
            AccountLink link = client.accountLinks().create(params);
            return link.getUrl();
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to open Stripe onboarding", e);
        }
    }

    public ConnectAccountStatus fetchStatus(String accountId) {
        requireKey();
        try {
            Account account = client.accounts().retrieve(accountId);
            return statusOf(account);
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to read the Stripe connected account", e);
        }
    }

    public static ConnectAccountStatus statusOf(Account account) {
        return new ConnectAccountStatus(
                account.getId(),
                Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getPayoutsEnabled()),
                Boolean.TRUE.equals(account.getDetailsSubmitted()));
    }

    /**
     * Hosted page collecting one booking fee. The charge is created on Alloquence's
     * account and immediately transferred to the restaurant's, minus the commission —
     * so the money is the restaurateur's from the outset.
     */
    public CheckoutSession createBookingFeeCheckout(BookingFeeCharge charge) {
        return createConnectedCheckout(charge.reservationId(), charge.amountCents(),
                charge.currency(), charge.applicationFeeCents(), charge.connectedAccountId(),
                charge.customerEmail(),
                "Frais de réservation — " + charge.restaurantName(),
                "Ces frais ne sont pas déduits de l'addition.");
    }

    /**
     * Collects the difference owed for guests added to an existing reservation.
     *
     * <p>The page says plainly that the larger party depends on a table still being free
     * when the money lands: nothing was held while the diner made up their mind, and
     * finding that out after paying would read as a bait and switch.
     */
    public CheckoutSession createPartySizeTopUpCheckout(PartySizeTopUpCharge charge) {
        return createConnectedCheckout(charge.reservationId(), charge.amountCents(),
                charge.currency(), charge.applicationFeeCents(), charge.connectedAccountId(),
                charge.customerEmail(),
                "Complément de couverts — " + charge.restaurantName(),
                charge.extraGuests() + " couvert(s) supplémentaire(s), sous réserve d'une table "
                        + "disponible au moment du règlement.",
                // Its own landing page: the ordinary one promises a confirmation, and
                // this payment may yet come back instead.
                topUpSuccessUrl());
    }

    private CheckoutSession createConnectedCheckout(UUID reservationId, int amountCents,
            String currency, int applicationFeeCents, String connectedAccountId,
            String customerEmail, String productName, String productDescription) {
        return createConnectedCheckout(reservationId, amountCents, currency, applicationFeeCents,
                connectedAccountId, customerEmail, productName, productDescription,
                connect.successUrl());
    }

    /** Falls back to the ordinary page rather than handing Stripe a null return URL. */
    private String topUpSuccessUrl() {
        String configured = connect.topUpSuccessUrl();
        return (configured == null || configured.isBlank()) ? connect.successUrl() : configured;
    }

    private CheckoutSession createConnectedCheckout(UUID reservationId, int amountCents,
            String currency, int applicationFeeCents, String connectedAccountId,
            String customerEmail, String productName, String productDescription,
            String successUrl) {
        requireKey();
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(successUrl)
                .setCancelUrl(connect.cancelUrl())
                .putMetadata(RESERVATION_METADATA_KEY, reservationId.toString())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(currency)
                                .setUnitAmount((long) amountCents)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(productName)
                                        .setDescription(productDescription)
                                        .build())
                                .build())
                        .build())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setApplicationFeeAmount((long) applicationFeeCents)
                        .setTransferData(SessionCreateParams.PaymentIntentData.TransferData.builder()
                                .setDestination(connectedAccountId)
                                .build())
                        .putMetadata(RESERVATION_METADATA_KEY, reservationId.toString())
                        .build());

        if (customerEmail != null && !customerEmail.isBlank()) {
            params.setCustomerEmail(customerEmail);
        }

        try {
            Session session = client.checkout().sessions().create(params.build());
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to open the reservation payment page", e);
        }
    }

    /**
     * Closes a checkout session so it can no longer be paid.
     *
     * <p>Called before opening a replacement: two live sessions for one reservation
     * would both be payable, and the second payment would have to be given back by hand.
     *
     * <p>Best effort — a session Stripe has already completed or expired cannot be
     * expired again, and that is not a problem worth failing the diner's click over.
     */
    public void expireCheckout(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        requireKey();
        try {
            client.checkout().sessions().expire(sessionId);
        } catch (StripeException e) {
            log.info("Could not expire checkout session {} ({}); it was most likely already closed",
                    sessionId, e.getMessage());
        }
    }

    /**
     * Gives the diner every cent back. The transfer to the restaurant is reversed and
     * Alloquence hands back its commission: a cancellation inside the promised window
     * costs the diner nothing, so it must not quietly cost them the platform's cut.
     *
     * @param idempotencyKey makes a retry after a lost response a no-op at Stripe rather
     *                       than a second refund
     */
    public void refundFully(String paymentIntentId, String idempotencyKey) {
        requireKey();
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setReverseTransfer(true)
                .setRefundApplicationFee(true)
                .build();
        try {
            Refund refund = client.refunds().create(params, options(null, idempotencyKey));
            // Stripe always returns an id, so the id proves nothing: the status does.
            // "pending" is a normal outcome on some payment methods and counts as success.
            if (REFUND_FAILED.contains(refund.getStatus())) {
                throw new PaymentGatewayException(
                        "Stripe refused the refund of " + paymentIntentId + ": " + refund.getStatus());
            }
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to refund the booking fee", e);
        }
    }

    /**
     * Sends a connected account's collected fees to its bank.
     *
     * @param idempotencyKey the payout record's own id, so a sweep that retries after
     *                       losing Stripe's answer cannot send the money twice
     */
    public String payOut(String accountId, int amountCents, String currency, String idempotencyKey) {
        requireKey();
        PayoutCreateParams params = PayoutCreateParams.builder()
                .setAmount((long) amountCents)
                .setCurrency(currency)
                .build();
        try {
            Payout payout = client.payouts().create(params, options(accountId, idempotencyKey));
            return payout.getId();
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to pay out to the connected account", e);
        }
    }

    /**
     * Hosted page that registers a card <em>without charging it</em>, for the no-show
     * guarantee.
     *
     * <p>Created on the restaurant's own account, not Alloquence's: it is the
     * restaurateur who will debit the card if the table is wasted, and a payment method
     * saved on one Stripe account cannot be used from another.
     */
    public CheckoutSession createCardRegistration(CardRegistration registration) {
        requireKey();
        RequestOptions onRestaurant = options(registration.connectedAccountId(), null);
        try {
            Customer customer = client.customers().create(
                    CustomerCreateParams.builder()
                            .setEmail(registration.customerEmail())
                            .setDescription("Garantie no-show — réservation " + registration.reservationId())
                            .build(),
                    onRestaurant);

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SETUP)
                    .setCurrency(registration.currency())
                    .setCustomer(customer.getId())
                    .setSuccessUrl(connect.successUrl())
                    .setCancelUrl(connect.cancelUrl())
                    .putMetadata(RESERVATION_METADATA_KEY, registration.reservationId().toString())
                    .build();

            Session session = client.checkout().sessions().create(params, onRestaurant);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to open the card registration page", e);
        }
    }

    /** The card a completed setup session saved, read back from the restaurant's account. */
    public RegisteredCard readRegisteredCard(String setupIntentId, String connectedAccountId) {
        requireKey();
        try {
            SetupIntent intent = client.setupIntents()
                    .retrieve(setupIntentId, options(connectedAccountId, null));
            return new RegisteredCard(intent.getCustomer(), intent.getPaymentMethod());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to read the registered card", e);
        }
    }

    /**
     * Debits a no-show penalty from a card registered earlier.
     *
     * <p>Charged directly on the restaurant's account and with no application fee: a
     * penalty compensates a table lost, and Alloquence takes no share of that.
     *
     * <p>{@code offSession} because nobody is at a browser — which also means the bank
     * may refuse for want of authentication, and that refusal is a normal outcome the
     * caller has to handle.
     */
    public String chargeNoShowPenalty(NoShowCharge charge) {
        requireKey();
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount((long) charge.amountCents())
                .setCurrency(charge.currency())
                .setCustomer(charge.customerId())
                .setPaymentMethod(charge.paymentMethodId())
                .setConfirm(true)
                .setOffSession(true)
                .setDescription("Absence non annulée — réservation " + charge.reservationId())
                .putMetadata(RESERVATION_METADATA_KEY, charge.reservationId().toString())
                .build();
        try {
            PaymentIntent intent = client.paymentIntents().create(params,
                    options(charge.connectedAccountId(), charge.idempotencyKey()));
            if (!"succeeded".equals(intent.getStatus())) {
                throw new PaymentGatewayException(
                        "Stripe did not settle the penalty: " + intent.getStatus());
            }
            return intent.getId();
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to charge the no-show penalty", e);
        }
    }

    /**
     * Forgets a diner's card once it can no longer be needed.
     *
     * <p>Best effort: a card already detached, or belonging to a deleted customer, is
     * the outcome we wanted anyway.
     */
    public void detachCard(String paymentMethodId, String connectedAccountId) {
        if (paymentMethodId == null || paymentMethodId.isBlank()) {
            return;
        }
        requireKey();
        try {
            client.paymentMethods().detach(paymentMethodId, options(connectedAccountId, null));
        } catch (StripeException e) {
            log.info("Could not detach payment method {} ({}); treating it as already gone",
                    paymentMethodId, e.getMessage());
        }
    }

    private static RequestOptions options(String stripeAccount, String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder();
        if (stripeAccount != null) {
            builder.setStripeAccount(stripeAccount);
        }
        if (idempotencyKey != null) {
            builder.setIdempotencyKey(idempotencyKey);
        }
        return builder.build();
    }

    private void requireKey() {
        if (stripe.secretKey() == null || stripe.secretKey().isBlank()) {
            throw new PaymentGatewayException("Stripe secret key is not configured (STRIPE_SECRET_KEY)");
        }
    }
}
