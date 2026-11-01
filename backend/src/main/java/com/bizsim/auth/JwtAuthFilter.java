package com.bizsim.auth;

import com.bizsim.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Turns "Authorization: Bearer &lt;jwt&gt;" into a {@link TenantContext.Principal}.
 *
 * <p>Public paths pass through untouched. Everything else is 401 without a valid token — including
 * unknown paths, which is deliberate: an unauthenticated caller learns nothing about the API shape.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/auth/login") || path.equals("/api/ping");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "missing bearer token");
            return;
        }
        try {
            Claims claims = jwtService.verify(header.substring(7));
            TenantContext.set(new TenantContext.Principal(
                    Long.parseLong(claims.getSubject()),
                    claims.get("tenantId", Number.class).longValue(),
                    claims.get("tenantCode", String.class),
                    claims.get("role", String.class)));
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            unauthorized(response, "invalid or expired token");
        } finally {
            TenantContext.clear();
        }
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
