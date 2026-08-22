package com.callbot.ai.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.callbot.ai.config.FrontendProperties;
import com.callbot.ai.model.Customer;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.Restaurant;

@Component
public class ReservationMessageTemplates {

    private static final DateTimeFormatter FR_DATE_TIME =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH);
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/Paris");

    private final FrontendProperties frontend;

    public ReservationMessageTemplates(FrontendProperties frontend) {
        this.frontend = frontend;
    }

    public String forClient(Reservation reservation, Customer customer, Restaurant restaurant) {
        String greeting = (customer != null && customer.getFirstName() != null)
                ? "Bonjour **" + customer.getFirstName() + "**"
                : "Bonjour";
        String when = formatDateTime(reservation.getStartsAt(), restaurant.getTimezone());
        String address = formatAddress(restaurant);
        String phone = formatPhoneLink(restaurant.getPhoneNumber());
        String rescheduleLink = buildRescheduleLink(reservation);

        return """
                %s,
                Votre réservation chez **%s** est confirmée ✅

                **Date** : %s
                **Nombre de personnes** : %d
                **Adresse** : %s
                **Téléphone** : %s

                -# Notre assistant s'est peut-être trompé de créneau ? [Choisissez un autre horaire](%s)

                À très vite !"""
                .formatted(greeting, restaurant.getName(), when, reservation.getPartySize(), address, phone, rescheduleLink);
    }

    public String forRestaurant(Reservation reservation, Customer customer, Restaurant restaurant) {
        String who = formatCustomerIdentity(customer);
        String when = formatDateTime(reservation.getStartsAt(), restaurant.getTimezone());
        String notes = (reservation.getNotes() != null && !reservation.getNotes().isBlank())
                ? reservation.getNotes()
                : "aucune";

        return """
                **Nouvelle réservation**

                **Client** : %s
                **Date** : %s
                **Nombre de personnes** : %d
                **Notes** : %s"""
                .formatted(who, when, reservation.getPartySize(), notes);
    }

    private String formatDateTime(OffsetDateTime dt, String timezone) {
        ZoneId zone = (timezone != null && !timezone.isBlank()) ? ZoneId.of(timezone) : FALLBACK_ZONE;
        return dt.atZoneSameInstant(zone).format(FR_DATE_TIME);
    }

    private String formatAddress(Restaurant r) {
        StringBuilder sb = new StringBuilder();
        if (r.getAddress() != null) sb.append(r.getAddress());
        if (r.getPostalCode() != null || r.getCity() != null) {
            if (sb.length() > 0) sb.append(", ");
            if (r.getPostalCode() != null) sb.append(r.getPostalCode()).append(' ');
            if (r.getCity() != null) sb.append(r.getCity());
        }
        if (sb.length() == 0) return "adresse non renseignée";
        String address = sb.toString().trim();
        String mapsUrl = "https://www.google.com/maps/search/?api=1&query="
                + URLEncoder.encode(address, StandardCharsets.UTF_8);
        return "[" + address + "](" + mapsUrl + ")";
    }

    private String formatPhoneLink(String phone) {
        if (phone == null || phone.isBlank()) return "non renseigné";
        String telHref = phone.replaceAll("[^+0-9]", "");
        return "[" + phone + "](tel:" + telHref + ")";
    }

    private String formatCustomerIdentity(Customer customer) {
        if (customer == null) return "Client inconnu";
        StringBuilder sb = new StringBuilder();
        if (customer.getFirstName() != null) sb.append(customer.getFirstName());
        if (customer.getLastName() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(customer.getLastName());
        }
        if (sb.length() == 0) sb.append("Client");
        if (customer.getPhone() != null) {
            sb.append(" — ").append(formatPhoneLink(customer.getPhone()));
        }
        return sb.toString();
    }

    private String buildRescheduleLink(Reservation reservation) {
        String base = (frontend.baseUrl() != null && !frontend.baseUrl().isBlank())
                ? frontend.baseUrl().replaceAll("/+$", "")
                : "";
        return base + "/client/reservations/" + reservation.getId() + "/reschedule";
    }
}
