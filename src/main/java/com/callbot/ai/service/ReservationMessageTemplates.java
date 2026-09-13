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
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.model.Restaurant;
import com.callbot.ai.model.RestaurantMenu;
import com.callbot.ai.repository.RestaurantMenuRepository;

@Component
public class ReservationMessageTemplates {

    private static final DateTimeFormatter FR_DATE_TIME =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH'h'mm", Locale.FRENCH);
    private static final ZoneId FALLBACK_ZONE = ZoneId.of("Europe/Paris");

    private final FrontendProperties frontend;
    private final RestaurantMenuRepository menuRepository;

    public ReservationMessageTemplates(FrontendProperties frontend, RestaurantMenuRepository menuRepository) {
        this.frontend = frontend;
        this.menuRepository = menuRepository;
    }

    public String forClient(Reservation reservation, Customer customer, Restaurant restaurant) {
        String greeting = (customer != null && customer.getFirstName() != null)
                ? "Bonjour **" + customer.getFirstName() + "**"
                : "Bonjour";
        String when = formatDateTime(reservation.getStartsAt(), restaurant.getTimezone());
        String address = formatAddress(restaurant);
        String phone = formatPhoneLink(restaurant.getPhoneNumber());
        String modificationLink = buildModificationLink(reservation);
        String menuLine = buildMenuLine(reservation, restaurant);

        return """
                %s,
                Votre réservation chez **%s** est confirmée ✅

                **Date** : %s
                **Nombre de personnes** : %d
                **Adresse** : %s
                **Téléphone** : %s
                %s
                -# Un imprévu, ou notre assistant s'est trompé ? [Modifier ma réservation](%s)

                À très vite !"""
                .formatted(greeting, restaurant.getName(), when, reservation.getPartySize(), address, phone,
                        menuLine, modificationLink);
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
     * carries both of the diner's remaining links, so they never have to phone to give a
     * table back — or to change how many of them are coming.
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

                -# Un changement ? [Modifier ma réservation](%s) — un empêchement ? [Annuler ma réservation](%s)

                À très vite !"""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAddress(restaurant),
                        formatAmount(reservation.getGuaranteeAmountCents(), reservation.getCurrency()),
                        refundWindowOf(reservation),
                        buildModificationLink(reservation),
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

    /**
     * Message 7: the party grew, and the extra guests have to be paid for.
     *
     * <p>Says the two things that would otherwise be discovered too late: the booking has
     * <em>not</em> changed yet, and the larger party depends on a table still being free
     * when the money lands. Nothing is held in the meantime, and a message that implied
     * otherwise would be selling a table twice.
     */
    public String forClientPartySizeTopUp(Reservation reservation, ReservationCharge topUp,
            Customer customer, Restaurant restaurant) {
        int extraGuests = topUp.getTargetPartySize() - reservation.getPartySize();
        return """
                %s,
                Vous souhaitez passer à **%d couverts** chez **%s** ⏳

                **Date** : %s
                **Réservation actuelle** : %d couverts
                **Complément à régler** : %s pour %d couvert(s) supplémentaire(s)

                Votre réservation reste confirmée à %d couverts tant que le complément
                n'est pas réglé. Le passage à %d couverts est **subordonné à une table
                disponible au moment du paiement**.

                👉 [Régler le complément](%s)

                -# Ce lien n'est valable que %d minutes."""
                .formatted(greeting(customer), topUp.getTargetPartySize(), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAmount(topUp.getAmountCents(), topUp.getCurrency()),
                        extraGuests,
                        reservation.getPartySize(),
                        topUp.getTargetPartySize(),
                        buildTopUpLink(topUp),
                        PartySizeTopUpService.SETTLEMENT_WINDOW.toMinutes());
    }

    public String forRestaurantPartySizeTopUp(Reservation reservation, ReservationCharge topUp,
            Customer customer, Restaurant restaurant) {
        return """
                **Complément de couverts demandé** ⏳

                **Client** : %s
                **Date** : %s
                **Couverts** : %d → %d (non appliqué)
                **Complément** : %s

                La réservation reste à %d couverts sur sa table actuelle. Aucune table
                n'est tenue pour la hausse : elle sera revérifiée au règlement."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(), topUp.getTargetPartySize(),
                        formatAmount(topUp.getAmountCents(), topUp.getCurrency()),
                        reservation.getPartySize());
    }

    /**
     * Message 8: the difference was paid, a table was free, the party is now larger.
     *
     * <p>The only message in the sequence that announces the change itself: everything
     * before it was careful to say the rise had <em>not</em> happened.
     */
    public String forClientPartySizeTopUpApplied(Reservation reservation, ReservationCharge topUp,
            Customer customer, Restaurant restaurant) {
        return """
                %s,
                C'est confirmé : votre réservation chez **%s** passe à **%d couverts** ✅

                **Date** : %s
                **Nombre de personnes** : %d
                **Complément réglé** : %s

                Une table pouvant vous accueillir était bien disponible. À très vite !"""
                .formatted(greeting(customer), restaurant.getName(), reservation.getPartySize(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAmount(topUp.getAmountCents(), topUp.getCurrency()));
    }

    public String forRestaurantPartySizeTopUpApplied(Reservation reservation,
            ReservationCharge topUp, Customer customer, Restaurant restaurant) {
        return """
                **Complément réglé, couverts appliqués** ✅

                **Client** : %s
                **Date** : %s
                **Nombre de personnes** : %d
                **Encaissé** : %s

                La table a été réattribuée si nécessaire."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatAmount(topUp.getAmountCents(), topUp.getCurrency()));
    }

    /**
     * Message 9: paid, and the room could not take them after all.
     *
     * <p>The awkward one, and the one that most needs writing plainly. The diner did
     * everything asked of them and got nothing for it, so the message leads with the
     * money being back and ends with the booking they still have — not with an apology
     * that leaves them wondering whether they still have a table at all.
     */
    public String forClientPartySizeTopUpRefunded(Reservation reservation,
            ReservationCharge topUp, Customer customer, Restaurant restaurant) {
        return """
                %s,
                Le passage à %d couverts chez **%s** n'a pas pu être honoré : plus aucune
                table ne pouvait vous accueillir sur ce créneau.

                **Remboursé** : %s

                Le remboursement apparaît sur votre relevé sous quelques jours ouvrés, sur
                le moyen de paiement utilisé.

                **Votre réservation reste confirmée** :

                **Date** : %s
                **Nombre de personnes** : %d

                Appelez le restaurant au %s si vous souhaitez trouver une autre solution."""
                .formatted(greeting(customer), topUp.getTargetPartySize(), restaurant.getName(),
                        formatAmount(topUp.getAmountCents(), topUp.getCurrency()),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatPhoneLink(restaurant.getPhoneNumber()));
    }

    /** The restaurateur has a diner who tried to pay and could not be served: they should know. */
    public String forRestaurantPartySizeTopUpRefunded(Reservation reservation,
            ReservationCharge topUp, Customer customer, Restaurant restaurant) {
        return """
                **Complément remboursé — aucune table disponible** 💶

                **Client** : %s
                **Date** : %s
                **Couverts** : %d → %d refusé
                **Remboursé** : %s

                Le client a réglé, mais aucune table ne pouvait accueillir %d personnes au
                moment du paiement. Sa réservation reste confirmée à %d couverts."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(), topUp.getTargetPartySize(),
                        formatAmount(topUp.getAmountCents(), topUp.getCurrency()),
                        topUp.getTargetPartySize(), reservation.getPartySize());
    }

    /**
     * Message 10: the window closed on a request nobody settled.
     *
     * <p>Says that nothing was taken and nothing changed, and that asking again is still
     * possible. A diner who let the link lapse by accident should not have to wonder
     * whether they have lost their table.
     */
    public String forClientPartySizeTopUpExpired(Reservation reservation,
            ReservationCharge topUp, Customer customer, Restaurant restaurant) {
        return """
                %s,
                Le délai pour régler le complément chez **%s** est écoulé. **Rien ne vous
                a été débité** et votre réservation n'a pas changé.

                **Date** : %s
                **Nombre de personnes** : %d

                Appelez le restaurant au %s si vous souhaitez toujours passer à %d couverts."""
                .formatted(greeting(customer), restaurant.getName(),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(),
                        formatPhoneLink(restaurant.getPhoneNumber()),
                        topUp.getTargetPartySize());
    }

    public String forRestaurantPartySizeTopUpExpired(Reservation reservation,
            ReservationCharge topUp, Customer customer, Restaurant restaurant) {
        return """
                **Complément non réglé — demande caduque**

                **Client** : %s
                **Date** : %s
                **Couverts** : %d → %d abandonné
                **Complément** : %s, jamais réglé

                La réservation est inchangée à %d couverts. Une nouvelle demande de hausse
                est de nouveau possible."""
                .formatted(formatCustomerIdentity(customer),
                        formatDateTime(reservation.getStartsAt(), restaurant.getTimezone()),
                        reservation.getPartySize(), topUp.getTargetPartySize(),
                        formatAmount(topUp.getAmountCents(), topUp.getCurrency()),
                        reservation.getPartySize());
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

    private String buildTopUpLink(ReservationCharge topUp) {
        return baseUrl() + "/client/reservations/complement/" + topUp.getPaymentToken();
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
        String modificationLink = buildModificationLink(reservation);
        String menuLine = buildMenuLine(reservation, restaurant);

        return """
                %s,
                Votre réservation chez **%s** a bien été mise à jour ✏️

                **Date** : %s
                **Nombre de personnes** : %d
                **Adresse** : %s
                **Téléphone** : %s
                %s
                -# Besoin de changer à nouveau ? [Modifier ma réservation](%s)

                À très vite !"""
                .formatted(greeting, restaurant.getName(), when, reservation.getPartySize(), address, phone,
                        menuLine, modificationLink);
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

    /**
     * The dining room's copy when a diner changed their own booking.
     *
     * <p>Says what moved, not only where it landed. "Table of four at 21 h" tells a
     * restaurant nothing it can act on; "six became four" frees a table in someone's head
     * before they have finished reading. Only the lines that actually changed carry the
     * before, so an hour that did not move does not look as though it might have.
     *
     * @param refundedAmountCents what went back to the diner, zero when nothing did. Named
     *                            out loud because the restaurateur's own takings just fell
     *                            and they should learn it here rather than from a statement.
     */
    public String forRestaurantModifiedByGuest(Reservation reservation, Customer customer,
            Restaurant restaurant, Integer previousPartySize, OffsetDateTime previousStartsAt,
            int refundedAmountCents) {
        String when = formatDateTime(reservation.getStartsAt(), restaurant.getTimezone());
        if (previousStartsAt != null && !previousStartsAt.isEqual(reservation.getStartsAt())) {
            when = formatDateTime(previousStartsAt, restaurant.getTimezone()) + " → **" + when + "**";
        }
        String covers = String.valueOf(reservation.getPartySize());
        if (previousPartySize != null && !previousPartySize.equals(reservation.getPartySize())) {
            covers = previousPartySize + " → **" + reservation.getPartySize() + "**";
        }
        String money = refundedAmountCents > 0
                ? "\n**Remboursé au client** : "
                        + formatAmount(refundedAmountCents, reservation.getCurrency())
                : "";

        return """
                **Réservation modifiée par le client**

                **Client** : %s
                **Date** : %s
                **Nombre de personnes** : %s%s"""
                .formatted(formatCustomerIdentity(customer), when, covers, money);
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

    /**
     * Where a diner changes their own covers and hour.
     *
     * <p>Keyed on the modification token, never the reservation's id: an id in a message
     * is an address anyone holding one can walk to, and the rest of the diner's journey
     * has always gone through a secret instead.
     */
    private String buildModificationLink(Reservation reservation) {
        return baseUrl() + "/client/reservations/modifier/" + reservation.getModificationToken();
    }

    /**
     * Ligne « Voir le menu », seulement si le restaurant a publie une carte. Le lien
     * porte l'identifiant de la reservation pour que la page propose d'y revenir.
     * Rendue comme un paragraphe a part : vide, elle n'ajoute qu'une ligne blanche.
     */
    private String buildMenuLine(Reservation reservation, Restaurant restaurant) {
        boolean published = menuRepository.findById(restaurant.getId())
                .map(menu -> !RestaurantMenu.MODE_NONE.equals(menu.getMode()))
                .orElse(false);
        if (!published) {
            return "";
        }
        return "\n-# Envie de découvrir la carte ? [Voir le menu](" + baseUrl()
                + "/client/restaurants/" + restaurant.getId() + "/menu?reservation=" + reservation.getPublicToken() + ")\n";
    }

    private String baseUrl() {
        return (frontend.baseUrl() != null && !frontend.baseUrl().isBlank())
                ? frontend.baseUrl().replaceAll("/+$", "")
                : "";
    }
}
