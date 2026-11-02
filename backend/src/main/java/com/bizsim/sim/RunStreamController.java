package com.bizsim.sim;

import com.bizsim.run.RunRepository;
import com.bizsim.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The live channel for one run (D20): a browser asks for a ticket on an authenticated POST, then
 * opens {@code EventSource} on {@code /events?ticket=...}. The ticket is single-use and lives 60
 * seconds, so the session token never appears in a URL.
 */
@RestController
@RequestMapping("/api/runs")
public class RunStreamController {

    private final RunRepository runs;
    private final StreamTicketService tickets;
    private final RunEventService events;

    public RunStreamController(RunRepository runs, StreamTicketService tickets, RunEventService events) {
        this.runs = runs;
        this.tickets = tickets;
        this.events = events;
    }

    @PostMapping("/{runId}/stream-ticket")
    public Map<String, Object> ticket(@PathVariable long runId) {
        TenantContext.Principal me = TenantContext.require();
        runs.find(runId, me.tenantId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such run"));

        StreamTicketService.Ticket ticket = tickets.issue(runId, me.tenantId(), me.userId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticket.value());
        body.put("expiresInSeconds", StreamTicketService.TTL.toSeconds());
        return body;
    }

    @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long runId, @RequestParam String ticket) {
        tickets.consume(ticket)
                .filter(consumed -> consumed.runId() == runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "invalid, expired or already used stream ticket"));
        return events.subscribe(runId);
    }
}
