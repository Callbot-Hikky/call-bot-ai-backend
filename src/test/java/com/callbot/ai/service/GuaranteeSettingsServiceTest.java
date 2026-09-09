package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

import com.callbot.ai.dto.GuaranteeSettingsRequest;
import com.callbot.ai.dto.GuaranteeSettingsResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.repository.RestaurantRepository;
import com.callbot.ai.security.CallerOrganizationResolver;

@ExtendWith(MockitoExtension.class)
class GuaranteeSettingsServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private CallerOrganizationResolver callerOrganization;
    @Mock
    private ConnectAccountService connectAccount;
    @InjectMocks
    private GuaranteeSettingsService service;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    private static final String OWNER = "owner@resto.fr";
    private static final String INTRUDER = "intruder@autre-resto.fr";

    private Restaurant restaurant() {
        return Restaurant.builder().id(restaurantId).organizationId(organizationId).build();
    }

    private void callerIsOwner() {
        when(callerOrganization.resolve(OWNER)).thenReturn(Optional.of(organizationId));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));
        when(restaurantRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void refusesAPayingModeWhileStripeHasNotClearedTheAccount() {
        when(callerOrganization.resolve(OWNER)).thenReturn(Optional.of(organizationId));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));
        doThrow(new InvalidRequestException("compte non validé"))
                .when(connectAccount).requireAbleToCharge(organizationId);

        assertThatThrownBy(() -> service.update(restaurantId,
                new GuaranteeSettingsRequest("booking_fee", 1500, null, 24), OWNER))
                .isInstanceOf(InvalidRequestException.class);

        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void staysFreeWithoutAskingStripeAnything() {
        callerIsOwner();

        service.update(restaurantId, new GuaranteeSettingsRequest("none", null, null, null), OWNER);

        verify(connectAccount, never()).requireAbleToCharge(any());
    }

    @Test
    void switchesToBookingFeeMode() {
        callerIsOwner();

        GuaranteeSettingsResponse response = service.update(restaurantId,
                new GuaranteeSettingsRequest("booking_fee", 1500, null, 24), OWNER);

        assertThat(response.mode()).isEqualTo("booking_fee");
        assertThat(response.bookingFeeCentsPerGuest()).isEqualTo(1500);
        assertThat(response.refundWindowHours()).isEqualTo(24);
    }

    @Test
    void refusesBookingFeeModeWithoutAnAmount() {
        when(callerOrganization.resolve(OWNER)).thenReturn(Optional.of(organizationId));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        assertThatThrownBy(() -> service.update(restaurantId,
                new GuaranteeSettingsRequest("booking_fee", null, null, 48), OWNER))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("bookingFeeCentsPerGuest");
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void refusesNoShowModeWithoutAPenalty() {
        when(callerOrganization.resolve(OWNER)).thenReturn(Optional.of(organizationId));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        assertThatThrownBy(() -> service.update(restaurantId,
                new GuaranteeSettingsRequest("no_show", null, null, 48), OWNER))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("noShowPenaltyCentsPerGuest");
        verify(restaurantRepository, never()).save(any());
    }

    @Test
    void refusesAnUnknownMode() {
        when(callerOrganization.resolve(OWNER)).thenReturn(Optional.of(organizationId));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        assertThatThrownBy(() -> service.update(restaurantId,
                new GuaranteeSettingsRequest("gratuit", null, null, 48), OWNER))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void goingBackToFreeClearsNothingButTheMode() {
        callerIsOwner();

        GuaranteeSettingsResponse response = service.update(restaurantId,
                new GuaranteeSettingsRequest("none", null, null, null), OWNER);

        assertThat(response.mode()).isEqualTo("none");
    }

    @Test
    void anotherOrganizationCannotReadTheSettings() {
        when(callerOrganization.resolve(INTRUDER)).thenReturn(Optional.of(UUID.randomUUID()));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        assertThatThrownBy(() -> service.get(restaurantId, INTRUDER))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anotherOrganizationCannotChangeTheSettings() {
        when(callerOrganization.resolve(INTRUDER)).thenReturn(Optional.of(UUID.randomUUID()));
        when(restaurantRepository.findById(restaurantId)).thenReturn(Optional.of(restaurant()));

        assertThatThrownBy(() -> service.update(restaurantId,
                new GuaranteeSettingsRequest("booking_fee", 1500, null, 48), INTRUDER))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(restaurantRepository, never()).save(any());
    }
}
