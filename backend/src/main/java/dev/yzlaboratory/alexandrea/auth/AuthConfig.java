package dev.yzlaboratory.alexandrea.auth;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the auth slice: enables {@link AuthProperties} binding and exposes
 * the {@link Clock} the stores and {@link TokenService} read "now" from.
 *
 * <p>The clock is a single injected bean (UTC system clock in production) rather
 * than scattered {@code Instant.now()} calls so token expiry is testable —
 * tests swap in a fixed/offset clock to fast-forward past a 24h TTL without
 * sleeping.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
