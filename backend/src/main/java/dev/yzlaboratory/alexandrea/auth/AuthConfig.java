package dev.yzlaboratory.alexandrea.auth;

import java.time.Clock;
import java.util.concurrent.Executor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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

    /**
     * Backs {@link dev.yzlaboratory.alexandrea.auth.mail.MailDispatcher}: a small
     * pool that drains on shutdown so a verification email already handed off is
     * not dropped on a graceful stop.
     */
    @Bean
    public Executor verificationMailExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("verification-mail-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
