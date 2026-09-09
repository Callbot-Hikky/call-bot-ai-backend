package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.UserRepository;
import com.callbot.ai.security.CallerOrganizationResolver;

@ExtendWith(MockitoExtension.class)
class ConnectAccountServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CallerOrganizationResolver callerOrganization;
    @Mock
    private StripeConnectGateway connect;
    @InjectMocks
    private ConnectAccountService service;

    private static final String OWNER = "owner@resto.fr";
    private static final String ACCOUNT = "acct_123";
    private final UUID organizationId = UUID.randomUUID();

    private Organization organization() {
        return Organization.builder().id(organizationId).name("Groupe Payant").build();
    }

    private void callerOwns(Organization organization) {
        when(callerOrganization.resolve(OWNER)).thenReturn(Optional.of(organizationId));
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));
    }

    @Test
    void opensAnAccountTheFirstTimeAndReusesItAfterwards() {
        Organization organization = organization();
        callerOwns(organization);
        when(userRepository.findByEmail(OWNER)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(OWNER).build()));
        when(connect.createConnectedAccount(OWNER, "Groupe Payant")).thenReturn(ACCOUNT);
        when(connect.createOnboardingLink(ACCOUNT)).thenReturn("https://connect.stripe.com/setup/1");
        when(organizationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.startOnboarding(OWNER).url()).isEqualTo("https://connect.stripe.com/setup/1");
        assertThat(organization.getStripeAccountId()).isEqualTo(ACCOUNT);

        // Second click: a new link, but never a second account.
        service.startOnboarding(OWNER);
        verify(connect, org.mockito.Mockito.times(1)).createConnectedAccount(anyString(), anyString());
    }

    @Test
    void refusesAChargeUntilStripeHasClearedTheAccount() {
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization()));

        assertThatThrownBy(() -> service.requireAbleToCharge(organizationId))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Stripe");
    }

    @Test
    void allowsAChargeOnceStripeHasClearedTheAccount() {
        Organization organization = organization();
        organization.setStripeChargesEnabled(true);
        when(organizationRepository.findById(organizationId)).thenReturn(Optional.of(organization));

        service.requireAbleToCharge(organizationId);
    }

    @Test
    void recordsTheMomentOnboardingUnlockedTheAccount() {
        Organization organization = organization();
        when(organizationRepository.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(organization));

        service.apply(new ConnectAccountStatus(ACCOUNT, true, true, true));

        assertThat(organization.isStripeChargesEnabled()).isTrue();
        assertThat(organization.getStripeOnboardedAt()).isNotNull();
    }

    @Test
    void aLaterAccountUpdateDoesNotRewriteTheOnboardingDate() {
        Organization organization = organization();
        organization.setStripeChargesEnabled(true);
        when(organizationRepository.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(organization));

        service.apply(new ConnectAccountStatus(ACCOUNT, true, false, true));

        assertThat(organization.getStripeOnboardedAt()).isNull();
        assertThat(organization.isStripePayoutsEnabled()).isFalse();
    }

    @Test
    void anAccountBelongingToNoOrganizationIsIgnoredRatherThanCrashingTheWebhook() {
        when(organizationRepository.findByStripeAccountId("acct_unknown")).thenReturn(Optional.empty());

        service.apply(new ConnectAccountStatus("acct_unknown", true, true, true));

        verify(organizationRepository, never()).save(any());
    }

    @Test
    void aCallerBoundToNoOrganizationCannotTouchAPaymentAccount() {
        when(callerOrganization.resolve(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startOnboarding(null))
                .isInstanceOf(InvalidRequestException.class);
    }
}
