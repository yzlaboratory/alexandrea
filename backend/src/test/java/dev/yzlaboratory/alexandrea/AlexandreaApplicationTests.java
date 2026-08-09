package dev.yzlaboratory.alexandrea;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yzlaboratory.alexandrea.auth.AuthProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the Spring application context against an isolated, in-memory SQLite URL
 * so the test does not depend on a file on disk.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:sqlite::memory:?journal_mode=WAL&foreign_keys=on",
    "spring.flyway.enabled=false",
})
@ActiveProfiles("dev")
class AlexandreaApplicationTests {

    @Autowired
    private AuthProperties authProperties;

    @Test
    void contextLoads() {
        // Intentionally empty: the test's only assertion is that the
        // @SpringBootTest context above builds without throwing.
    }

    /**
     * Every other test overrides {@link AuthProperties} with a hand-built
     * instance, so this is the only place a typo'd YAML key under
     * {@code alexandrea.auth} would actually surface — nested records bind
     * here the same way the flat fields already do.
     */
    @Test
    void rateLimitConfigBindsFromApplicationYml() {
        assertThat(authProperties.loginRateLimit())
            .isEqualTo(new AuthProperties.RateLimit(10, Duration.ofMinutes(15)));
        assertThat(authProperties.mailRateLimit())
            .isEqualTo(new AuthProperties.RateLimit(5, Duration.ofHours(1)));
    }
}
