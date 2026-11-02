package com.bizsim.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Optional single-port deployment: if a built console exists, the backend serves it.
 *
 * <p>Why this exists: the console is one origin away from being usable wherever the API is reachable.
 * Development uses Vite on 5173 (with hot reload); production ships {@code web/dist} and needs no
 * second port, no second proxy and no CORS rules.
 *
 * <p>If the directory is absent the handler is not registered at all, so an API-only deployment
 * behaves exactly as before.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    private final Path root;

    public WebUiConfig(@Value("${bizsim.web.dir:./web/dist}") String directory) {
        this.root = Paths.get(directory).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!Files.isDirectory(root)) {
            return;
        }
        // "/**" so that /assets/*, favicon and anything else the bundle references resolve under
        // the same directory; controllers still win for /api/**.
        registry.addResourceHandler("/**")
                .addResourceLocations(root.toUri().toString());
    }

    @Controller
    static class IndexController {

        private final Path root;

        IndexController(WebUiConfig config) {
            this.root = config.root;
        }

        @GetMapping({"/", "/index.html"})
        public ResponseEntity<Resource> index() {
            Path index = root.resolve("index.html");
            if (!Files.isRegularFile(index)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(new FileSystemResource(index));
        }
    }
}
