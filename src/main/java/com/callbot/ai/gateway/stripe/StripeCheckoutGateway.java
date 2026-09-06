package com.callbot.ai.gateway.stripe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.callbot.ai.exception.PaymentGatewayException;
import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.PaymentGateway;
import com.callbot.ai.model.OfferPlan;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

/**
 * Stripe Checkout adapter. Active while {@code app.payment.provider} is {@code stripe}
 * (the default); another provider's adapter takes over by flipping that property.
 */
@Component
@ConditionalOnProperty(prefix = "app.payment", name = "provider", havingValue = StripeCheckoutGateway.PROVIDER_CODE,
        matchIfMissing = true)
public class StripeCheckoutGateway implements PaymentGateway {

    static final String PROVIDER_CODE = "stripe";

    private final StripeProperties properties;
    private final StripeClient client;

    public StripeCheckoutGateway(StripeProperties properties) {
        this.properties = properties;
        // StripeClient just stores the key; a blank key is rejected lazily at call time.
        this.client = new StripeClient(properties.secretKey() == null ? "" : properties.secretKey());
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public CheckoutSession createSubscriptionCheckout(OfferPlan plan, String customerEmail) {
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            throw new PaymentGatewayException("Stripe secret key is not configured (STRIPE_SECRET_KEY)");
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(withSessionIdTemplate(properties.successUrl()))
                .setCancelUrl(properties.cancelUrl())
                .setCustomerEmail(customerEmail)
                .addLineItem(lineItemFor(plan))
                .build();

        try {
            Session session = client.checkout().sessions().create(params);

            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Unable to create the Stripe checkout session", e);
        }
    }

    private SessionCreateParams.LineItem lineItemFor(OfferPlan plan) {
        SessionCreateParams.LineItem.Builder lineItem = SessionCreateParams.LineItem.builder().setQuantity(1L);

        String priceId = properties.priceIdFor(plan.getCode());
        if (priceId != null) {
            // Preferred: reference a Price created in the Stripe dashboard / API.
            return lineItem.setPrice(priceId).build();
        }

        // Fallback: build the price inline so checkout works without a pre-created Price.
        return lineItem
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(plan.getCurrency())
                        .setUnitAmount((long) plan.getAmountCents())
                        .setRecurring(SessionCreateParams.LineItem.PriceData.Recurring.builder()
                                .setInterval(intervalOf(plan))
                                .build())
                        .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName(plan.getLabel())
                                .build())
                        .build())
                .build();
    }

    // Stripe replaces the {CHECKOUT_SESSION_ID} literal so the success page can look the session up.
    private static String withSessionIdTemplate(String successUrl) {
        String separator = successUrl.contains("?") ? "&" : "?";

        return successUrl + separator + "session_id={CHECKOUT_SESSION_ID}";
    }

    private static SessionCreateParams.LineItem.PriceData.Recurring.Interval intervalOf(OfferPlan plan) {
        return "year".equalsIgnoreCase(plan.getInterval())
                ? SessionCreateParams.LineItem.PriceData.Recurring.Interval.YEAR
                : SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH;
    }
}
