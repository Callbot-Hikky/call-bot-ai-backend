package com.callbot.ai.gateway.stripe;

import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.CheckoutSession;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Payout;
import com.stripe.model.Refund;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
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

    /** Ties a Stripe object back to the reservation it paid for. */
    public static final String RESERVATION_METADATA_KEY = "reservationId";

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
        requireKey();
        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(connect.successUrl())
                .setCancelUrl(connect.cancelUrl())
                .putMetadata(RESERVATION_METADATA_KEY, charge.reservationId().toString())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(charge.currency())
                                .setUnitAmount((long) charge.amountCents())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Frais de réservation — " + charge.restaurantName())
                                        .setDescription("Ces frais ne sont pas déduits de l'addition.")
                                        .build())
                                .build())
                        .build())
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setApplicationFeeAmount((long) charge.applicationFeeCents())
                        .setTransferData(SessionCreateParams.PaymentIntentData.TransferData.builder()
                                .setDestination(charge.connectedAccountId())
                                .build())
                        .putMetadata(RESERVATION_METADATA_KEY, charge.reservationId().toString())
                        .build());

        if (charge.customerEmail() != null && !charge.customerEmail().isBlank()) {
            params.setCustomerEmail(charge.customerEmail());
        }

        try {
            Session session = client.checkout().sessions().create(params.build());
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to open the reservation payment page", e);
        }
    }

    /**
     * Gives the diner every cent back. The transfer to the restaurant is reversed and
     * Alloquence hands back its commission: a cancellation inside the promised window
     * costs the diner nothing, so it must not quietly cost them the platform's cut.
     */
    public void refundFully(String paymentIntentId) {
        requireKey();
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(paymentIntentId)
                .setReverseTransfer(true)
                .setRefundApplicationFee(true)
                .build();
        try {
            Refund refund = client.refunds().create(params);
            if (refund.getId() == null) {
                throw new PaymentGatewayException("Stripe refused the refund of " + paymentIntentId);
            }
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to refund the booking fee", e);
        }
    }

    /** Sends a connected account's collected fees to its bank. */
    public String payOut(String accountId, int amountCents, String currency) {
        requireKey();
        PayoutCreateParams params = PayoutCreateParams.builder()
                .setAmount((long) amountCents)
                .setCurrency(currency)
                .build();
        RequestOptions options = RequestOptions.builder().setStripeAccount(accountId).build();
        try {
            Payout payout = client.payouts().create(params, options);
            return payout.getId();
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to pay out to the connected account", e);
        }
    }

    private void requireKey() {
        if (stripe.secretKey() == null || stripe.secretKey().isBlank()) {
            throw new PaymentGatewayException("Stripe secret key is not configured (STRIPE_SECRET_KEY)");
        }
    }
}
