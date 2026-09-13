package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.model.GuaranteeStatus;
import com.callbot.ai.model.Reservation;
import com.callbot.ai.model.ReservationStatus;
import com.callbot.ai.notification.ReservationGuaranteeExpiredEvent;
import com.callbot.ai.repository.ReservationRepository;

import lombok.RequiredArgsConstructor;

/**
 * Releases tables held by diners who never secured their reservation.
 *
 * <p>A pre-held reservation blocks its table exactly like a confirmed one. Without
 * this sweep, a single phone call could freeze a Saturday evening for free — which
 * is precisely what the feature exists to prevent.
 */
@Component
@RequiredArgsConstructor
public class GuaranteeExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(GuaranteeExpiryJob.class);

    private final ReservationRepository reservationRepository;
    private final PartySizeTopUpService topUps;
    private final ApplicationEventPublisher events;

    @Scheduled(fixedDelayString = "${app.guarantee.expiry-scan-interval-ms:60000}")
    @Transactional
    public void releaseExpiredHolds() {
        List<Reservation> expired = reservationRepository.lockExpiredHolds(
                ReservationStatus.AWAITING_PAYMENT, OffsetDateTime.now());
        if (expired.isEmpty()) {
            return;
        }
        for (Reservation reservation : expired) {
            // The table is going back on sale; a request to grow the party on it can
            // only be for nothing. Rare — it takes a rise on a hold — but it would
            // otherwise leave a payable link on a released reservation.
            topUps.lapsePendingFor(reservation.getId(), "the reservation's hold expired");

            reservation.setStatus(ReservationStatus.CANCELLED);
            reservation.setGuaranteeStatus(GuaranteeStatus.EXPIRED);
            reservation.setCancelledAt(OffsetDateTime.now());
            // The link is dead: paying now would buy a table that has gone back on sale.
            reservation.setPaymentToken(null);
            reservation.setGuaranteeExpiresAt(null);
            reservationRepository.save(reservation);
            events.publishEvent(new ReservationGuaranteeExpiredEvent(reservation.getId()));
        }
        log.info("Released {} reservation(s) whose payment window had closed", expired.size());
    }
}
