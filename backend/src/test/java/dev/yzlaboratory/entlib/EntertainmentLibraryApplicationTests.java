package dev.yzlaboratory.entlib;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test for the scaffold. Boots the Spring application context against
 * an isolated, in-memory SQLite URL so the test does not depend on a file on
 * disk. If wiring regresses — a bean fails to resolve, a config conflict,
 * autoconfig misbehaviour — this is what catches it before any real feature
 * test runs.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:?journal_mode=WAL&foreign_keys=on",
    "spring.flyway.enabled=false",
})
@ActiveProfiles("dev")
class EntertainmentLibraryApplicationTests {

    @Test
    void contextLoads() {
        // Body intentionally empty. The assertion is "Spring booted".
    }
}
