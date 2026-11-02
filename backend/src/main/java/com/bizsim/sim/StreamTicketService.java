package com.bizsim.sim;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-use, short-lived tickets for the event stream (D20).
 *
 * <p>A browser's {@code EventSource} cannot set an Authorization header, and putting a session JWT in
 * a query string means it lands in access logs, proxy logs and the browser history. So the client
 * asks for a ticket over an ordinary authenticated POST, then opens the stream with that ticket: it
 * is bound to one run and one tenant, expires in a minute, and is destroyed the moment it is used.
 */
@Service
public class StreamTicketService {

    static final Duration TTL = Duration.ofSeconds(60);

    public record Ticket(String value, long runId, long tenantId, long userId, Instant expiresAt) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public Ticket issue(long runId, long tenantId, long userId) {
        prune();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Ticket ticket = new Ticket(value, runId, tenantId, userId, Instant.now().plus(TTL));
        tickets.put(value, ticket);
        return ticket;
    }

    /** Removes the ticket whether or not it was valid: a scraped ticket cannot be retried. */
    public Optional<Ticket> consume(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(value);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    private void prune() {
        Instant now = Instant.now();
        tickets.values().removeIf(ticket -> ticket.expiresAt().isBefore(now));
    }
}
