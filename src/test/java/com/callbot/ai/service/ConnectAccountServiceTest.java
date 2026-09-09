package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.User;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.repository.UserRepository;
import com.callbot.ai.security.OrganizationScope;

@ExtendWith(MockitoExtension.class)
class ConnectAccountServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OrganizationScope scope;
    @Mock
    private StripeConnectGateway connect;
    @InjectMocks
    private ConnectAccountService service;

    private static final String OWNER = "owner@resto.fr";
    private static final String ACCOUNT = "acct_123";
    private final UUID restaurantId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    private Restaurant restaurant() {
        return Restaurant.builder().id(restaurantId).organizationId(organizationId)
                .name("Chez Payant").build();
    }

    @Test
    void opensAnAccountForTheRestaurantTheFirstTimeAndReusesItAfterwards() {
        Restaurant restaurant = restaurant();
        when(scope.ownedRestaurant(restaurantId, OWNER)).thenReturn(restaurant);
        when(scope.organizationOf(OWNER)).thenReturn(Optional.of(organizationId));
        when(userRepository.findByEmail(OWNER)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(OWNER).build()));
        // The account is opened in the restaurant's name, not the owner's organization's.
        when(connect.createConnectedAccount(OWNER, "Chez Payant")).thenReturn(ACCOUNT);
        when(connect.createOnboardingLink(ACCOUNT)).thenReturn("https://connect.stripe.com/setup/1");
        when(restaurantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.startOnboarding(restaurantId, OWNER).url())
                .isEqualTo("https://connect.stripe.com/setup/1");
        assertThat(restaurant.getStripeAccountId()).isEqualTo(ACCOUNT);

        // Second click: a new link, but never a second account.
        service.startOnboarding(restaurantId, OWNER);
        verify(connect, times(1)).createConnectedAccount(anyString(), anyString());
    }

    @Test
    void twoRestaurantsOfTheSameOwnerGetTwoAccounts() {
        // A Stripe account is tied to a legal entity and a bank; two establishments of
        // one owner are often two companies, and must not share one.
        UUID otherRestaurantId = UUID.randomUUID();
        Restaurant first = restaurant();
        Restaurant second = Restaurant.builder().id(otherRestaurantId)
                .organizationId(organizationId).name("Chez Payant Bis").build();
        when(scope.ownedRestaurant(restaurantId, OWNER)).thenReturn(first);
        when(scope.ownedRestaurant(otherRestaurantId, OWNER)).thenReturn(second);
        when(scope.organizationOf(OWNER)).thenReturn(Optional.of(organizationId));
        when(userRepository.findByEmail(OWNER)).thenReturn(Optional.of(
                User.builder().id(UUID.randomUUID()).email(OWNER).build()));
        when(connect.createConnectedAccount(anyString(), anyString()))
                .thenReturn(ACCOUNT, "acct_456");
        when(connect.createOnboardingLink(anyString())).thenReturn("https://connect.stripe.com/setup/1");
        when(restaurantRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.startOnboarding(restaurantId, OWNER);
        service.startOnboarding(otherRestaurantId, OWNER);

        assertThat(first.getStripeAccountId()).isNotEqualTo(second.getStripeAccountId());
    }

    @Test
    void refusesAChargeUntilStripeHasClearedTheAccount() {
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        assertThatThrownBy(() -> service.requireAbleToCharge(restaurantId))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Stripe");
    }

    @Test
    void allowsAChargeOnceStripeHasClearedTheAccount() {
        Restaurant restaurant = restaurant();
        restaurant.setStripeChargesEnabled(true);
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant));

        service.requireAbleToCharge(restaurantId);
    }

    @Test
    void recordsTheMomentOnboardingUnlockedTheAccount() {
        Restaurant restaurant = restaurant();
        when(restaurantRepository.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(restaurant));

        service.apply(new ConnectAccountStatus(ACCOUNT, true, true, true));

        assertThat(restaurant.isStripeChargesEnabled()).isTrue();
        assertThat(restaurant.getStripeOnboardedAt()).isNotNull();
    }

    @Test
    void aLaterAccountUpdateDoesNotRewriteTheOnboardingDate() {
        Restaurant restaurant = restaurant();
        restaurant.setStripeChargesEnabled(true);
        when(restaurantRepository.findByStripeAccountId(ACCOUNT)).thenReturn(Optional.of(restaurant));

        service.apply(new ConnectAccountStatus(ACCOUNT, true, false, true));

        assertThat(restaurant.getStripeOnboardedAt()).isNull();
        assertThat(restaurant.isStripePayoutsEnabled()).isFalse();
    }

    @Test
    void anAccountBelongingToNoRestaurantIsIgnoredRatherThanCrashingTheWebhook() {
        when(restaurantRepository.findByStripeAccountId("acct_unknown")).thenReturn(Optional.empty());

        service.apply(new ConnectAccountStatus("acct_unknown", true, true, true));

        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void theMicroserviceCannotOpenAPaymentAccount() {
        when(scope.ownedRestaurant(restaurantId, null)).thenReturn(restaurant());
        when(scope.organizationOf(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startOnboarding(restaurantId, null))
                .isInstanceOf(InvalidRequestException.class);
        verify(connect, never()).createConnectedAccount(anyString(), anyString());
    }

    @Test
    void aDisputeIsCountedAgainstTheRestaurantThatTookTheFee() {
        Restaurant restaurant = restaurant();

        service.recordDispute(restaurant);

        assertThat(restaurant.getStripeDisputeCount()).isEqualTo(1);
        assertThat(restaurant.getStripeLastDisputeAt()).isNotNull();
    }
}
