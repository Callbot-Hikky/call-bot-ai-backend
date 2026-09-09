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

    /**
     * Message 1 of the guarantee flow: the diner has just hung up and owes a booking fee.
     * It states the amount, the refund rule and the deadline, because that is what the
     * restaurateur is legally required to have told them — and what stops the payment
     * from looking like a scam when it lands.
     */
    public String forClientAwaitingBookingFee(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                %s,
                Votre table chez **%s** est retenue, il ne manque que le règlement ⏳

                **Date** : %s
                **Nombre de personnes** : %d
                **Frais de réservation** : %s

                Ces frais ne sont pas déduits de l'addition. Ils vous sont intégralement
                remboursés si vous annulez plus de %d h avant le service.

                👉 [Régler et confirmer ma réservation](%s)

                -# Votre table n'est retenue que %d minutes. Passé ce délai, elle sera remise à disposition."""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()),
                        restaurant.getRefundWindowHours(),
                        buildPaymentLink(reservation),
                        GuaranteePolicy.PAYMENT_WINDOW.toMinutes());
    }

    /**
     * Message 2 of the guarantee flow: no money is taken now, only a card is kept. Said
     * plainly and before the form, so a later debit never comes as a surprise.
     */
    public String forClientAwaitingNoShowGuarantee(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                %s,
                Votre table chez **%s** est retenue, il ne manque qu'une empreinte bancaire ⏳

                **Date** : %s
                **Nombre de personnes** : %d

                **Aucun montant ne sera débité maintenant.** En cas d'absence non annulée,
                %s seront prélevés.

                👉 [Enregistrer ma carte et confirmer](%s)

                -# Votre table n'est retenue que %d minutes. Passé ce délai, elle sera remise à disposition."""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()),
                        buildPaymentLink(reservation),
                        GuaranteePolicy.PAYMENT_WINDOW.toMinutes());
    }

    /** Message 3 of the guarantee flow: the window closed and the table went back on sale. */
    public String forClientGuaranteeExpired(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                %s,
                Votre table chez **%s** du %s n'a pas pu être confirmée ⌛

                Le délai de %d minutes est écoulé et la table a été remise à disposition.
                Rien ne vous a été débité.

                Vous pouvez rappeler le restaurant au %s pour réserver à nouveau."""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        GuaranteePolicy.PAYMENT_WINDOW.toMinutes(),
                        formatPhoneLink(restaurant.getPhoneNumber()));
    }

    public String forRestaurantGuaranteeExpired(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                **Réservation non confirmée** ⌛

                **Client** : %s
                **Date** : %s
                **Nombre de personnes** : %d

                Le règlement n'est pas arrivé dans le délai imparti. La table est de nouveau disponible."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize());
    }

    /**
     * Message 4: the fee is in and the table is theirs. It repeats the refund rule and
     * carries the cancellation link, so the diner never has to phone to give a table back.
     */
    public String forClientConfirmedAfterPayment(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                %s,
                Votre réservation chez **%s** est confirmée ✅

                **Date** : %s
                **Nombre de personnes** : %d
                **Adresse** : %s
                **Réglé** : %s

                Ces frais ne sont pas déduits de l'addition. Ils vous seront intégralement
                remboursés si vous annulez plus de %d h avant le service.

                -# Un empêchement ? [Annuler ma réservation](%s)

                À très vite !"""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAddress(restaurant),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()),
                        refundWindowOf(reservation),
                        buildCancellationLink(reservation));
    }

    /**
     * Message 5: the reservation is cancelled and nothing comes back. Says why in one
     * line — a diner who reads only that the money is kept will assume a mistake.
     */
    public String forClientCancellationConfirmed(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        String moneyNote = reservation.getGuaranteeAmountCents() == null
                ? "Rien ne vous a été débité."
                : ("Les frais de réservation restent acquis au restaurant : l'annulation "
                        + "intervient moins de " + refundWindowOf(reservation) + " h avant le service.");

        return """
                %s,
                Votre réservation chez **%s** du %s est annulée.

                %s

                Vous pouvez rappeler le restaurant au %s pour réserver à nouveau."""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        moneyNote,
                        formatPhoneLink(restaurant.getPhoneNumber()));
    }

    /** Message 6: cancelled in time, money on its way back. */
    public String forClientRefundIssued(Reservation reservation, Customer customer,
            Restaurant restaurant, int refundedAmountCents) {
        return """
                %s,
                Votre réservation chez **%s** du %s est annulée, et vos frais de
                réservation vous sont remboursés 💶

                **Remboursé** : %s

                Le remboursement apparaît sur votre relevé sous quelques jours ouvrés,
                sur le moyen de paiement utilisé.

                Vous pouvez rappeler le restaurant au %s pour réserver à nouveau."""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        formatAmount(refundedAmountCents, reservation.getCurrency()),
                        formatPhoneLink(restaurant.getPhoneNumber()));
    }

    public String forRestaurantConfirmedAfterPayment(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                **Réservation réglée et confirmée** ✅

                **Client** : %s
                **Date** : %s
                **Nombre de personnes** : %d
                **Encaissé** : %s"""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()));
    }

    /** The restaurateur needs to know the table is free again, and whether they keep the fee. */
    public String forRestaurantCancelledByGuest(Reservation reservation, Customer customer,
            Restaurant restaurant, boolean refunded) {
        String moneyNote = refunded
                ? "Les frais ont été remboursés au client (annulation dans les délais)."
                : (reservation.getGuaranteeAmountCents() == null
                        ? "Aucun montant n'était en jeu."
                        : "Les frais de " + formatAmount(reservation.getGuaranteeAmountCents(),
                                reservation.getCurrency()) + " vous restent acquis.");

        return """
                **Réservation annulée par le client**

                **Client** : %s
                **Date** : %s
                **Nombre de personnes** : %d

                %s
                La table est de nouveau disponible."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        moneyNote);
    }

    /**
     * Message 6: the penalty was taken.
     *
     * <p>Says what happened, how much, and who to talk to. A debit nobody explains is a
     * debit the diner disputes with their bank, which costs everyone more than the meal.
     */
    public String forClientPenaltyCharged(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                %s,
                Vous n'avez pas honoré votre réservation du %s chez **%s**, et elle
                n'avait pas été annulée.

                **Débité** : %s

                Ce montant correspond à la garantie acceptée lors de votre réservation.
                Si vous pensez qu'il s'agit d'une erreur, appelez le restaurant au %s :
                lui seul peut y revenir."""
                .formatted(greeting(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        restaurant.getName(),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()),
                        formatPhoneLink(restaurant.getPhoneNumber()));
    }

    public String forRestaurantPenaltyCharged(Reservation reservation, Customer customer,
            Restaurant restaurant) {
        return """
                **Pénalité no-show encaissée** 💶

                **Client** : %s
                **Date** : %s
                **Nombre de personnes** : %d
                **Encaissé** : %s

                Aucune commission n'est prélevée sur cette somme."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()));
    }

    /**
     * Told to the restaurateur only. The diner is not chased for a failed debit: nothing
     * left their account, and it is now between them and the restaurant.
     */
    public String forRestaurantPenaltyAbandoned(Reservation reservation, Customer customer,
            Restaurant restaurant, String reason) {
        return """
                **Pénalité no-show non recouvrée** ⚠️

                **Client** : %s
                **Date** : %s
                **Montant attendu** : %s

                La carte a été refusée à deux reprises, à 24 h d'écart. Nous n'essaierons
                plus. Motif du dernier refus : %s

                Vous pouvez contacter le client directement au %s."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()),
                        reason == null ? "non précisé" : reason,
                        customer != null && customer.getPhone() != null
                                ? customer.getPhone()
                                : "numéro inconnu");
    }

    /** The window frozen on the reservation, which is the one the diner was promised. */
    private int refundWindowOf(Reservation reservation) {
        return reservation.getGuaranteeRefundWindowHours() == null
                ? 0
                : reservation.getGuaranteeRefundWindowHours();
    }

    private String buildCancellationLink(Reservation reservation) {
        return baseUrl() + "/client/reservations/annuler/" + reservation.getCancellationToken();
    }

    private String greeting(Customer customer) {
        return (customer != null && customer.getFirstName() != null)
                ? "Bonjour **" + customer.getFirstName() + "**"
                : "Bonjour";
    }

    /** Amounts live in cents throughout; diners read euros. */
    private String formatAmount(Integer amountCents, String currency) {
        if (amountCents == null) {
            return "montant non renseigné";
        }
        String symbol = "eur".equalsIgnoreCase(currency) ? "€" : currency;
        return String.format(Locale.FRENCH, "%.2f %s", amountCents / 100.0, symbol);
    }

    private String buildPaymentLink(Reservation reservation) {
        return baseUrl() + "/client/reservations/payer/" + reservation.getPaymentToken();
    }

    public String forClientUpdated(Reservation reservation, Customer customer, Restaurant restaurant) {
        String greeting = (customer != null && customer.getFirstName() != null)
                ? "Bonjour **" + customer.getFirstName() + "**"
                : "Bonjour";
        String when = formatDateTime(reservation.getStartsAt(), restaurant.getTimezone());
        String address = formatAddress(restaurant);
        String phone = formatPhoneLink(restaurant.getPhoneNumber());
        String rescheduleLink = buildRescheduleLink(reservation);

        return """
                %s,
                Votre réservation chez **%s** a bien été mise à jour ✏️

                **Date** : %s
                **Nombre de personnes** : %d
                **Adresse** : %s
                **Téléphone** : %s

                -# Besoin de changer à nouveau ? [Choisissez un autre horaire](%s)

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

    public String forRestaurantUpdated(Reservation reservation, Customer customer, Restaurant restaurant) {
        String who = formatCustomerIdentity(customer);
        String when = formatDateTime(reservation.getStartsAt(), restaurant.getTimezone());
        String notes = (reservation.getNotes() != null && !reservation.getNotes().isBlank())
                ? reservation.getNotes()
                : "aucune";

        return """
                **Réservation modifiée**

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
        return baseUrl() + "/client/reservations/" + reservation.getId() + "/reschedule";
    }

    private String baseUrl() {
        return (frontend.baseUrl() != null && !frontend.baseUrl().isBlank())
                ? frontend.baseUrl().replaceAll("/+$", "")
                : "";
    }
}
