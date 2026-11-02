package com.bizsim.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The round clock (ruling D12): when a round's deadline passes, the round closes and computes itself.
 *
 * <p>It calls the same transactional method as the facilitator button, so whichever happens first
 * wins and the other becomes a no-op. Failures are logged per run and never stop the sweep — one
 * broken run must not freeze the other four running in the same tenant.
 */
@Component
public class RoundClock {

    private static final Logger log = LoggerFactory.getLogger(RoundClock.class);

    private final RoundService rounds;
    private final RoundRepository repository;

    public RoundClock(RoundService rounds, RoundRepository repository) {
        this.rounds = rounds;
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 5000L, initialDelay = 10000L)
    public void tick() {
        for (long[] due : repository.dueRounds()) {
            long runId = due[0];
            long tenantId = due[1];
            try {
                rounds.closeAndCompute(tenantId, runId)
                        .ifPresent(r -> log.info("round clock closed run {} ({} companies)", runId,
                                r.teams().size()));
            } catch (RuntimeException e) {
                log.warn("round clock could not close run {}: {}", runId, e.getMessage());
            }
        }
    }
}
