package com.bizsim.sim;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.bizsim.run.RunRepository;
import com.bizsim.tenant.TenantContext;
import com.bizsim.web.ProblemException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * D102: sharing the position map, and the page a class opens.
 *
 * <p>The share endpoint sits under {@code /api/runs/{runId}}, so it inherits the session's own access:
 * a facilitator may share any round of a run in their tenant, a trainee their own. The page itself is
 * deliberately outside {@code /api} and outside the console: it is opened by people with no account, on
 * a phone, from a link — so it must not need a token, and it must not be indexed.
 */
@RestController
public class MapShareController {

    private final MapShareService maps;
    private final MapShareRepository shares;
    private final RunShareAccess access;

    public MapShareController(MapShareService maps, MapShareRepository shares, RunShareAccess access) {
        this.maps = maps;
        this.shares = shares;
        this.access = access;
    }

    public record ShareRequest(Integer round, String title) {
    }

    public record ShareResponse(long id, String token, String url, String title, Integer round, Instant expiresAt) {
    }

    /**
     * Freezes the run's picture into a link. The response carries the absolute URL, built from the
     * request, so the console does not have to know its own public address.
     */
    @PostMapping("/api/runs/{runId}/map-share")
    public ShareResponse share(@PathVariable long runId, @RequestBody(required = false) ShareRequest body) {
        TenantContext.Principal me = TenantContext.require();
        access.requireReader(me, runId);
        Integer round = body == null ? null : body.round();
        String title = body == null ? null : body.title();
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        MapShareService.ShareLink link = maps.share(me.tenantId(), runId, round, title, base);
        return new ShareResponse(link.id(), link.token(), link.url(), link.title(), link.round(), link.expiresAt());
    }

    /** What has been shared from this run, so a facilitator can see and withdraw links. */
    @GetMapping("/api/runs/{runId}/map-share")
    public List<ShareResponse> list(@PathVariable long runId) {
        TenantContext.Principal me = TenantContext.require();
        access.requireReader(me, runId);
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return shares.list(me.tenantId(), runId).stream()
                .map(share -> new ShareResponse(share.id(), null, base + "/share/map/…",
                        share.title(), share.round(), share.expiresAt()))
                .toList();
    }

    @PostMapping("/api/runs/{runId}/map-share/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable long runId, @PathVariable long id) {
        TenantContext.Principal me = TenantContext.require();
        access.requireReader(me, runId);
        if (!shares.revoke(me.tenantId(), id)) {
            throw ProblemException.notFound("map_share.not_found", "no such link, or already withdrawn");
        }
        return Map.of("revoked", true);
    }

    /**
     * The page. No authentication on purpose: that is what "sharable to players" means. A withdrawn or
     * expired link answers 404 — never a login prompt, which would read as a broken link.
     */
    @GetMapping(value = "/share/map/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String token) {
        MapShareRepository.Share share = maps.find(token);
        if (share == null || !share.live(Instant.now())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<!doctype html><meta charset=utf-8><title>Link not found</title>"
                            + "<p style=\"font-family:sans-serif;padding:2rem\">This link is not available. "
                            + "It may have been withdrawn, or it has expired.</p>");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex")
                .body(share.html());
    }
}
