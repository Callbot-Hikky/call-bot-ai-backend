package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.dto.CheckoutSessionResponse;
import com.callbot.ai.dto.CheckoutSummaryResponse;
import com.callbot.ai.dto.OfferResponse;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.CheckoutSession;
import com.callbot.ai.gateway.PaymentEvent;
import com.callbot.ai.gateway.PaymentEventType;
import com.callbot.ai.gateway.PaymentGateway;
import com.callbot.ai.model.OfferPlan;
import com.callbot.ai.model.OfferSubscription;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.OfferSubscriptionRepository;
import com.callbot.ai.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class OfferServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OfferSubscriptionRepository offerSubscriptionRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @InjectMocks
    private OfferService offerService;

    @Test
    void listOffers_returnsTheProPlan() {
        List<OfferResponse> offers = offerService.listOffers();

        assertThat(offers).extracting(OfferResponse::code).contains("pro");
        assertThat(offers).extracting(OfferResponse::amountCents).contains(9900);
    }

    @Test
    void checkout_persistsPendingSubscriptionAndReturnsCheckoutUrl() {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).organizationId(orgId).email("owner@resto.fr").build();

        when(userRepository.findByEmail("owner@resto.fr")).thenReturn(Optional.of(user));
        when(paymentGateway.providerCode()).thenReturn("stripe");
        when(paymentGateway.createSubscriptionCheckout(eq(OfferPlan.PRO), eq("owner@resto.fr")))
                .thenReturn(new CheckoutSession("cs_test_123", "https://checkout.stripe.com/c/cs_test_123"));

        CheckoutSessionResponse response = offerService.checkout("pro", "owner@resto.fr");

        assertThat(response.sessionId()).isEqualTo("cs_test_123");
        assertThat(response.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/cs_test_123");

        ArgumentCaptor<OfferSubscription> captor = ArgumentCaptor.forClass(OfferSubscription.class);
        verify(offerSubscriptionRepository).save(captor.capture());
        OfferSubscription saved = captor.getValue();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getOfferCode()).isEqualTo("pro");
        assertThat(saved.getAmountCents()).isEqualTo(9900);
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getCheckoutSessionId()).isEqualTo("cs_test_123");
        assertThat(saved.getProvider()).isEqualTo("stripe");
    }

    @Test
    void checkout_unknownOffer_throwsAndSkipsGateway() {
        assertThatThrownBy(() -> offerService.checkout("unknown", "owner@resto.fr"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(paymentGateway, never()).createSubscriptionCheckout(any(), any());
        verify(offerSubscriptionRepository, never()).save(any());
    }

    @Test
    void getCheckoutSummary_returnsRecapForOwnOrganization() {
        UUID orgId = UUID.randomUUID();
        User user = User.builder().id(UUID.randomUUID()).organizationId(orgId).email("owner@resto.fr").build();
        OfferSubscription subscription = OfferSubscription.builder()
                .organizationId(orgId)
                .offerCode("pro")
                .amountCents(9900)
                .currency("eur")
                .status("active")
                .checkoutSessionId("cs_test_123")
                .build();

        when(userRepository.findByEmail("owner@resto.fr")).thenReturn(Optional.of(user));
        when(offerSubscriptionRepository.findByCheckoutSessionId("cs_test_123"))
                .thenReturn(Optional.of(subscription));

        CheckoutSummaryResponse summary = offerService.getCheckoutSummary("cs_test_123", "owner@resto.fr");

        assertThat(summary.offerCode()).isEqualTo("pro");
        assertThat(summary.amountCents()).isEqualTo(9900);
        assertThat(summary.status()).isEqualTo("active");
    }

    @Test
    void getCheckoutSummary_otherOrganization_throwsNotFound() {
        User user = User.builder().id(UUID.randomUUID()).organizationId(UUID.randomUUID()).email("intruder@resto.fr").build();
        OfferSubscription subscription = OfferSubscription.builder()
                .organizationId(UUID.randomUUID())
                .checkoutSessionId("cs_test_123")
                .build();

        when(userRepository.findByEmail("intruder@resto.fr")).thenReturn(Optional.of(user));
        when(offerSubscriptionRepository.findByCheckoutSessionId("cs_test_123"))
                .thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> offerService.getCheckoutSummary("cs_test_123", "intruder@resto.fr"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void handle_checkoutCompleted_activatesTheSubscription() {
        OfferSubscription subscription = OfferSubscription.builder()
                .organizationId(UUID.randomUUID())
                .offerCode("pro")
                .status("pending")
                .checkoutSessionId("cs_test_123")
                .build();

        when(offerSubscriptionRepository.findByCheckoutSessionId("cs_test_123"))
                .thenReturn(Optional.of(subscription));

        offerService.handle(new PaymentEvent(PaymentEventType.CHECKOUT_COMPLETED, "cs_test_123", "sub_123"));

        assertThat(subscription.getStatus()).isEqualTo("active");
        assertThat(subscription.getProviderSubscriptionId()).isEqualTo("sub_123");
        verify(offerSubscriptionRepository).save(subscription);
    }

    @Test
    void handle_unknownSession_isIgnored() {
        when(offerSubscriptionRepository.findByCheckoutSessionId("cs_unknown")).thenReturn(Optional.empty());

        offerService.handle(new PaymentEvent(PaymentEventType.CHECKOUT_COMPLETED, "cs_unknown", "sub_123"));

        verify(offerSubscriptionRepository, never()).save(any());
    }
}
