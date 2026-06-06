package dev.yzlaboratory.entlib;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the entertainment-library backend.
 *
 * <p>The backend is the Spring Boot half of the stack pinned by ADR 0014.
 * It serves the REST API behind CloudFront's {@code /api/*} behaviour, with
 * SQLite as the single datastore and Spring Security (ADR 0021) owning auth
 * state in-process. This class itself is intentionally bare — feature
 * surfaces land via {@code /to-issues} and {@code /implement-issues} against
 * the feature tickets in the GitHub tracker.
 */
@SpringBootApplication
public class EntertainmentLibraryApplication {

    public static void main(String[] args) {
        SpringApplication.run(EntertainmentLibraryApplication.class, args);
    }
}
