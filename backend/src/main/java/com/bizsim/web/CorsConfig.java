package com.bizsim.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for a split deployment, disabled by default.
 *
 * <p>The shipped layout puts nginx in front of both the console and the API, so the browser only ever
 * talks to one origin and CORS is not needed. If the console is hosted elsewhere, list its origin in
 * {@code BIZSIM_CORS_ORIGINS} (comma separated) and it starts working — an empty value registers no
 * mapping at all, so the default is "no cross-origin callers".
 *
 * <p>Deliberately no wildcard: an origin list that can be typed is an origin list that can be
 * reviewed. Credentials are not allowed either, because the API authenticates with a bearer token
 * rather than a cookie.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] origins;

    public CorsConfig(@Value("${bizsim.cors.origins:}") String configured) {
        this.origins = configured == null || configured.isBlank()
                ? new String[0]
                : configured.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }
}
