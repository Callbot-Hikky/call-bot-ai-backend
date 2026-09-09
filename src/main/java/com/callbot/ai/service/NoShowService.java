package com.callbot.ai.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.dto.ReservationResponse;
import com.callbot.ai.exception.InvalidRequestException;
import com.callbot.ai.exception.ResourceNotFoundException;
import com.callbot.ai.model.GuaranteeMode;
import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.repository.ReservationRepository;
import com.callbot.ai.security.OrganizationScope;

import lombok.RequiredArgsConstructor;

/**
 * Recording that a diner did not turn up, and taking it back.
 *
 * <p>Always a human act. The system never infers an absence from its own silence: a
 * diner who ate and whose table nobody marked is a diner who came, and stays one.
 *
 * <p>Recording does not debit. It starts a clock — {@link #CANCELLATION_WINDOW} — during
 * which the staff member who pressed the wrong row can undo it. Only when that window
 * closes does the penalty become chargeable, which is what makes a mistaken tap
 * harmless rather than a debit to explain to a customer.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class NoShowService {

    /** Time staff have to take back an absence before anyone's card is touched. */
    public static final Duration CANCELLATION_WINDOW = Duration.ofHours(2);

    private final ReservationRepository reservationRepository;
    private final OrganizationScope scope;

    public ReservationResponse record(UUID reservationId, String callerEmail) {
        Reservation reservation = owned(reservationId, callerEmail);
        UUID staffId = requireSignedInStaff(callerEmail);

        requireRecordable(reservation);

        reservation.setStatus(ReservationStatus.NO_SHOW);
        reservation.setNoShowRecordedAt(OffsetDateTime.now());
        reservation.setNoShowRecordedBy(staffId);
        if (chargeable(reservation)) {
            reservation.setPenaltyDueAt(OffsetDateTime.now().plus(CANCELLATION_WINDOW));
        }
        return ReservationResponse.from(reservationRepository.save(reservation));
    }

    /**
     * Takes back an absence. Refused once the penalty has been taken: the money is gone,
     * and pretending otherwise would leave the diner charged for a reservation the
     * system says they honoured.
     */
    public ReservationResponse undo(UUID reservationId, String callerEmail) {
        Reservation reservation = owned(reservationId, callerEmail);
        if (reservation.getNoShowRecordedAt() == null) {
            throw new InvalidRequestException("Aucune absence n'a été constatée sur cette réservation");
        }
        if (reservation.getPenaltyChargedAt() != null) {
            throw new InvalidRequestException(
                    "La pénalité a déjà été débitée : le remboursement doit être fait à la main");
        }
        if (chargeInFlight(reservation)) {
            // The window has closed and the bank is being asked right now. Retracting
            // here would leave the diner charged for a reservation marked as honoured.
            throw new InvalidRequestException(
                    "Le débit est en cours : réessayez dans un instant");
        }
        requireSignedInStaff(callerEmail);

        // Back to confirmed, not completed: staff may be taking back a mistaken tap on a
        // service still under way, and closing it would be a second wrong statement.
        reservation.setStatus(ReservationStatus.CONFIRMED);
        reservation.setNoShowRecordedAt(null);
        reservation.setNoShowRecordedBy(null);
        reservation.setPenaltyDueAt(null);
        reservation.setPenaltyAttempts(0);
        return ReservationResponse.from(reservationRepository.save(reservation));
    }

    /**
     * A person, not the AI microservice. Both recording an absence and taking one back
     * are judgements about what happened in the dining room, and an API key is nobody.
     */
    private UUID requireSignedInStaff(String callerEmail) {
        return scope.userIdOf(callerEmail)
                .orElseThrow(() -> new InvalidRequestException(
                        "Seul un membre du personnel signé peut se prononcer sur une absence"));
    }

    /** Claimed by the penalty sweep: the window has closed and the bank is answering. */
    private boolean chargeInFlight(Reservation reservation) {
        return reservation.getPenaltyAttempts() > 0
                && reservation.getPenaltyDueAt() == null
                && reservation.getPenaltyChargedAt() == null
                && GuaranteeStatus.SECURED.equals(reservation.getGuaranteeStatus());
    }

    /** A penalty is only owed where one was agreed and a card actually registered. */
    private boolean chargeable(Reservation reservation) {
        return GuaranteeMode.NO_SHOW.code().equals(reservation.getGuaranteeMode())
                && GuaranteeStatus.SECURED.equals(reservation.getGuaranteeStatus())
                && reservation.getStripePaymentMethodId() != null
                && reservation.getGuaranteeAmountCents() != null;
    }

    private void requireRecordable(Reservation reservation) {
        if (reservation.getNoShowRecordedAt() != null) {
            throw new InvalidRequestException("L'absence est déjà constatée");
        }
        if (ReservationStatus.CANCELLED.equals(reservation.getStatus())) {
            throw new InvalidRequestException(
                    "Cette réservation est annulée : personne n'était attendu");
        }
        if (reservation.getStartsAt().isAfter(OffsetDateTime.now())) {
            // Nobody is absent from a meal that has not started.
            throw new InvalidRequestException("Le service n'a pas encore commencé");
        }
        if (reservation.getEndsAt()
                .plus(NoShowPenaltyService.CARD_RETENTION_AFTER_SERVICE)
                .isBefore(OffsetDateTime.now())) {
            // The card was forgotten. Recording now would promise a debit that can only
            // fail, so the refusal is said here rather than discovered two days later.
            throw new InvalidRequestException(
                    "Ce service est trop ancien : le moyen de paiement du client n'est plus conservé");
        }
    }

    private Reservation owned(UUID reservationId, String callerEmail) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation", reservationId));
        scope.requireOwnedThrough(reservation.getRestaurantId(), "Reservation", reservationId, callerEmail);
        return reservation;
    }
}
