package com.callbot.ai.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.CheckoutSessionResponse;
import com.callbot.ai.dto.CheckoutSummaryResponse;
import com.callbot.ai.dto.OfferResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.PaymentEvent;
import com.callbot.ai.gateway.PaymentGateway;
import com.callbot.ai.model.OfferPlan;
import com.callbot.ai.model.OfferSubscription;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.OfferSubscriptionRepository;
import com.callbot.ai.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OfferService {

    private final UserRepository userRepository;
    private final OfferSubscriptionRepository offerSubscriptionRepository;
    private final PaymentGateway paymentGateway;

    /** The plans a customer can subscribe to. */
    public List<OfferResponse> listOffers() {
        return Arrays.stream(OfferPlan.values())
                .map(OfferResponse::from)
                .toList();
    }

    /**
     * Opens a hosted checkout session for the authenticated customer and records a
     * pending subscription. The frontend redirects the browser to the returned url.
     *
     * @param offerCode     the plan to subscribe to (e.g. "pro")
     * @param customerEmail email of the authenticated user (JWT subject)
     */
    @Transactional
    public CheckoutSessionResponse checkout(String offerCode, String customerEmail) {
        OfferPlan plan = OfferPlan.fromCode(offerCode)
                .orElseThrow(() -> new ResourceNotFoundException("Offer", offerCode));

        User user = userRepository.findByEmail(customerEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + customerEmail));

        CheckoutSession session = paymentGateway.createSubscriptionCheckout(plan, customerEmail);

        OfferSubscription subscription = OfferSubscription.builder()
                .organizationId(user.getOrganizationId())
                .userId(user.getId())
                .offerCode(plan.getCode())
                .amountCents(plan.getAmountCents())
                .currency(plan.getCurrency())
                .status("pending")
                .provider(paymentGateway.providerCode())
                .checkoutSessionId(session.id())
                .build();
        offerSubscriptionRepository.save(subscription);

        return new CheckoutSessionResponse(session.id(), session.url());
    }

    /**
     * Returns the recap for a checkout session, for the success page. Scoped to the caller's
     * organization so a user cannot read another organization's subscription.
     *
     * @param checkoutSessionId the checkout session id (from the success url query param)
     * @param requesterEmail    email of the authenticated user requesting the summary
     */
    @Transactional(readOnly = true)
    public CheckoutSummaryResponse getCheckoutSummary(String checkoutSessionId, String requesterEmail) {
        User user = userRepository.findByEmail(requesterEmail)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + requesterEmail));

        OfferSubscription subscription = offerSubscriptionRepository.findByCheckoutSessionId(checkoutSessionId)
                .filter(item -> item.getOrganizationId().equals(user.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Checkout session", checkoutSessionId));

        return CheckoutSummaryResponse.from(subscription);
    }

    /**
     * Reacts to a verified provider notification. Kept in the domain (rather than in the
     * webhook controller) so every provider adapter triggers the exact same transition.
     *
     * <p>Idempotent and tolerant of unknown sessions: a webhook may arrive twice, or for a
     * session we did not create.
     *
     * @param event the normalised payment event
     */
    @Transactional
    public void handle(PaymentEvent event) {
        switch (event.type()) {
            case CHECKOUT_COMPLETED ->
                activateFromSession(event.checkoutSessionId(), event.providerSubscriptionId());
        }
    }

    private void activateFromSession(String checkoutSessionId, String providerSubscriptionId) {
        offerSubscriptionRepository.findByCheckoutSessionId(checkoutSessionId).ifPresent(subscription -> {
            subscription.setStatus("active");
            subscription.setProviderSubscriptionId(providerSubscriptionId);
            offerSubscriptionRepository.save(subscription);
        });
    }
}
