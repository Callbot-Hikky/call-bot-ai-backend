package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.ConnectAccountResponse;
import com.callbot.ai.dto.ConnectOnboardingResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.gateway.stripe.ConnectAccountStatus;
import com.callbot.ai.gateway.stripe.StripeConnectGateway;
import com.callbot.ai.model.Organization;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.OrganizationRepository;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.UserRepository;
import com.callbot.ai.security.CallerOrganizationResolver;

import lombok.RequiredArgsConstructor;

/**
 * The restaurateur's payment account: opening it, and knowing what it may do.
 *
 * <p>An organization holds one connected account, not each restaurant: a group running
 * several restaurants banks once. The account is what makes a paying guarantee mode
 * possible at all, so {@link #requireAbleToCharge} is the gate every paying flow passes.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ConnectAccountService {

    private static final Logger log = LoggerFactory.getLogger(ConnectAccountService.class);

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final CallerOrganizationResolver callerOrganization;
    private final ReservationRepository reservationRepository;
    private final StripeConnectGateway connect;

    @Transactional(readOnly = true)
    public ConnectAccountResponse status(String callerEmail) {
        return describe(callerOrganizationOrFail(callerEmail));
    }

    /**
     * Opens the account on first call, then hands back a fresh onboarding link.
     *
     * <p>Stripe's links expire within minutes, so this is called again every time the
     * restaurateur clicks — never cached.
     */
    public ConnectOnboardingResponse startOnboarding(String callerEmail) {
        Organization organization = callerOrganizationOrFail(callerEmail);

        if (organization.getStripeAccountId() == null) {
            String email = userRepository.findByEmail(callerEmail).map(User::getEmail).orElse(null);
            organization.setStripeAccountId(
                    connect.createConnectedAccount(email, organization.getName()));
            organizationRepository.save(organization);
        }

        return new ConnectOnboardingResponse(connect.createOnboardingLink(organization.getStripeAccountId()));
    }

    /**
     * Records what Stripe now allows. Called from the webhook, and again whenever the
     * back-office asks — a restaurateur returning from onboarding should not have to
     * wait on a webhook to see their account unlocked.
     */
    public void apply(ConnectAccountStatus status) {
        organizationRepository.findByStripeAccountId(status.accountId())
                .ifPresentOrElse(
                        organization -> save(organization, status),
                        () -> log.warn("Stripe account {} belongs to no organization", status.accountId()));
    }

    public ConnectAccountResponse refresh(String callerEmail) {
        Organization organization = callerOrganizationOrFail(callerEmail);
        if (organization.getStripeAccountId() == null) {
            return describe(organization);
        }
        save(organization, connect.fetchStatus(organization.getStripeAccountId()));
        return describe(organization);
    }

    /**
     * Refuses anything that would take a diner's money through an account Stripe has
     * not cleared: the diner would reach a payment page that fails, having already been
     * told their table is held.
     */
    public void requireAbleToCharge(UUID organizationId) {
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", organizationId));
        if (!organization.isStripeChargesEnabled()) {
            throw new InvalidRequestException(
                    "Le compte de paiement n'est pas encore validé par Stripe : "
                            + "terminez l'inscription avant d'activer un mode payant.");
        }
    }

    private void save(Organization organization, ConnectAccountStatus status) {
        boolean wasBlocked = !organization.isStripeChargesEnabled();
        organization.setStripeChargesEnabled(status.chargesEnabled());
        organization.setStripePayoutsEnabled(status.payoutsEnabled());
        organization.setStripeDetailsSubmitted(status.detailsSubmitted());
        if (wasBlocked && status.chargesEnabled()) {
            organization.setStripeOnboardedAt(OffsetDateTime.now());
        }
        organizationRepository.save(organization);
    }

    /** The dispute count is only meaningful next to how many fees were actually taken. */
    private ConnectAccountResponse describe(Organization organization) {
        return ConnectAccountResponse.from(organization,
                reservationRepository.countPaidFor(organization.getId()));
    }

    private Organization callerOrganizationOrFail(String callerEmail) {
        UUID organizationId = callerOrganization.resolve(callerEmail)
                .orElseThrow(() -> new InvalidRequestException(
                        "Seul un utilisateur signé peut gérer un compte de paiement"));
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization", organizationId));
    }
}
