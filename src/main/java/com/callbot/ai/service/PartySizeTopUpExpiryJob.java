package com.callbot.ai.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.callbot.ai.model.ChargeKind;
import com.callbot.ai.model.ChargeStatus;
import com.callbot.ai.model.ReservationCharge;
import com.callbot.ai.notification.ReservationTopUpExpiredEvent;
import com.callbot.ai.repository.ReservationChargeRepository;

import lombok.RequiredArgsConstructor;

/**
 * Closes requests for a larger party that nobody settled in time.
 *
 * <p>Unlike the sweep that releases unpaid holds, nothing is being freed here: a request
 * held no table, and the reservation it hangs off never moved. What is at stake is the
 * reservation's future — one request may run at a time, so a request left pending for
 * ever would refuse every later rise, on a table whose party may since have grown twice.
 *
 * <p>The diner is told, because silence would be indistinguishable from a link that is
 * still worth following.
 */
@Component
@RequiredArgsConstructor
public class PartySizeTopUpExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(PartySizeTopUpExpiryJob.class);

    private final ReservationChargeRepository charges;
    private final PartySizeTopUpService topUps;
    private final ApplicationEventPublisher events;

    @Scheduled(fixedDelayString = "${app.top-up.expiry-scan-interval-ms:60000}")
    @Transactional
    public void lapseExpiredTopUps() {
        List<ReservationCharge> expired = charges.lockExpiredTopUps(
                ChargeKind.PARTY_SIZE_TOP_UP, ChargeStatus.PENDING, OffsetDateTime.now());
        if (expired.isEmpty()) {
            return;
        }
        for (ReservationCharge charge : expired) {
            topUps.lapse(charge, "the settlement window closed");
            events.publishEvent(
                    new ReservationTopUpExpiredEvent(charge.getReservationId(), charge.getId()));
        }
        log.info("Closed {} top-up request(s) whose settlement window had passed", expired.size());
    }
}
