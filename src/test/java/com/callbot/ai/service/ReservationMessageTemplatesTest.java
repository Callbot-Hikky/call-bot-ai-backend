package com.callbot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.callbot.ai.config.FrontendProperties;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantMenu;
import com.callbot.ai.repository.RestaurantMenuRepository;

@ExtendWith(MockitoExtension.class)
class ReservationMessageTemplatesTest {

    @Mock
    private RestaurantMenuRepository menuRepository;

    private final UUID restaurantId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID token = UUID.randomUUID();

    private ReservationMessageTemplates templates(String baseUrl) {
        return new ReservationMessageTemplates(new FrontendProperties(baseUrl), menuRepository);
    }

    private Reservation reservation() {
        return Reservation.builder()
                .id(reservationId)
                .publicToken(token)
                .restaurantId(restaurantId)
                .startsAt(OffsetDateTime.parse("2026-09-20T19:30:00+02:00"))
                .endsAt(OffsetDateTime.parse("2026-09-20T21:00:00+02:00"))
                .partySize(2)
                .build();
    }

    private Restaurant restaurant() {
        return Restaurant.builder().id(restaurantId).organizationId(UUID.randomUUID())
                .name("Chez Hikky").phoneNumber("+33100000000").timezone("Europe/Paris").build();
    }

    private Customer customer() {
        return Customer.builder().id(UUID.randomUUID()).restaurantId(restaurantId)
                .firstName("Marie").phone("+33600000000").build();
    }

    private void menuPublished(String mode) {
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.of(
                RestaurantMenu.builder().restaurantId(restaurantId).mode(mode).manualContent("{}").build()));
    }

    @Test
    void confirmation_includesMenuLinkWithReservationId_whenAMenuIsPublished() {
        menuPublished("images");

        String message = templates("https://app.hikky.fr/").forClient(reservation(), customer(), restaurant());

        assertThat(message).contains("[Voir le menu](https://app.hikky.fr/client/restaurants/"
                + restaurantId + "/menu?reservation=" + token + ")");
        // Le lien de replanification a cede la place a celui de modification, qui couvre
        // l'horaire ET les couverts.
        assertThat(message).contains("[Modifier ma réservation](https://app.hikky.fr/client/reservations/modifier/");
    }

    @Test
    void confirmation_hasNoMenuLine_whenNothingIsPublished() {
        menuPublished("none");

        String message = templates("https://app.hikky.fr").forClient(reservation(), customer(), restaurant());

        assertThat(message).doesNotContain("Voir le menu");
        assertThat(message).contains("Modifier ma réservation");
    }

    @Test
    void confirmation_hasNoMenuLine_whenTheRestaurantNeverCreatedAMenu() {
        when(menuRepository.findById(restaurantId)).thenReturn(Optional.empty());

        String message = templates("https://app.hikky.fr").forClient(reservation(), customer(), restaurant());

        assertThat(message).doesNotContain("Voir le menu");
    }

    @Test
    void update_includesMenuLinkToo() {
        menuPublished("manual");

        String message = templates("https://app.hikky.fr").forClientUpdated(reservation(), customer(), restaurant());

        assertThat(message).contains("a bien été mise à jour");
        assertThat(message).contains("/client/restaurants/" + restaurantId + "/menu?reservation=" + token);
        assertThat(message).doesNotContain(reservationId.toString());
    }

    @Test
    void links_areRelative_whenNoFrontendBaseUrlIsConfigured() {
        menuPublished("pdf");

        String message = templates("").forClient(reservation(), null, restaurant());

        assertThat(message).contains("[Voir le menu](/client/restaurants/" + restaurantId + "/menu?reservation=");
        assertThat(message).startsWith("Bonjour,");
    }

    @Test
    void restaurantMessage_neverCarriesClientLinks() {
        String message = templates("https://app.hikky.fr").forRestaurant(reservation(), customer(), restaurant());

        assertThat(message).doesNotContain("Voir le menu");
        assertThat(message).doesNotContain("reschedule");
    }
}
