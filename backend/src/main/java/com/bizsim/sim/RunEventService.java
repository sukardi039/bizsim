package com.bizsim.sim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-sent events, one stream per run (D20).
 *
 * <p>Chosen over STOMP: every message here is facilitator-driven and one-way — "round opened",
 * "round closed", "a company submitted" — and the browser already speaks SSE without a client
 * library or a reconnect protocol. A WebSocket would add a dependency on both ends to carry the same
 * few events.
 *
 * <p>Emitters are registered and removed under the same lock structure, so a client that disconnects
 * mid-broadcast cannot leave a dead emitter behind and slow every later publish.
 */
@Service
public class RunEventService {

    private static final Logger log = LoggerFactory.getLogger(RunEventService.class);
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> streams = new ConcurrentHashMap<>();

    public SseEmitter subscribe(long runId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streams.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("run", runId)));
        } catch (IOException e) {
            remove(runId, emitter);
        }
        return emitter;
    }

    public void publish(long runId, String type, Object payload) {
        List<SseEmitter> subscribers = streams.get(runId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name(type).data(payload));
            } catch (IOException | IllegalStateException e) {
                // the client went away; the next publish must not pay for it
                remove(runId, emitter);
            }
        }
    }

    public int subscriberCount(long runId) {
        List<SseEmitter> subscribers = streams.get(runId);
        return subscribers == null ? 0 : subscribers.size();
    }

    /**
     * Publishes only if the surrounding transaction commits. A rolled-back round must not leave
     * "round closed" on every player's screen.
     */
    public void publishAfterCommit(long runId, String type, Object payload) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(runId, type, payload);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish(runId, type, payload);
            }
        });
    }

    /** Keeps proxies from closing a quiet stream and lets the client notice a dead one. */
    @Scheduled(fixedDelay = 20_000L)
    public void heartbeat() {
        streams.forEach((runId, subscribers) -> {
            for (SseEmitter emitter : subscribers) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data(Map.of("run", runId)));
                } catch (IOException | IllegalStateException e) {
                    remove(runId, emitter);
                }
            }
        });
    }

    private void remove(long runId, SseEmitter emitter) {
        List<SseEmitter> subscribers = streams.get(runId);
        if (subscribers != null) {
            subscribers.remove(emitter);
            if (subscribers.isEmpty()) {
                streams.remove(runId);
            }
        }
        log.debug("stream closed for run {}", runId);
    }
}
