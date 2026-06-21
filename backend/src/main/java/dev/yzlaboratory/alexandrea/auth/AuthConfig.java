package dev.yzlaboratory.alexandrea.auth;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a single injected {@link Clock} (system UTC in prod) rather than
 * scattered {@code Instant.now()} calls, so token expiry is testable — tests
 * swap in an offset clock to fast-forward past a TTL without sleeping.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
