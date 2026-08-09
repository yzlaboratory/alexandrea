package dev.yzlaboratory.alexandrea.auth;

import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
public class AuthCleanupScheduler implements SchedulingConfigurer {

    private final TokenService tokenService;
    private final EmailChangeTokenStore emailChangeTokenStore;
    private final RateLimitBucketStore rateLimitBucketStore;
    private final AuthProperties properties;

    public AuthCleanupScheduler(
        TokenService tokenService,
        EmailChangeTokenStore emailChangeTokenStore,
        RateLimitBucketStore rateLimitBucketStore,
        AuthProperties properties
    ) {
        this.tokenService = tokenService;
        this.emailChangeTokenStore = emailChangeTokenStore;
        this.rateLimitBucketStore = rateLimitBucketStore;
        this.properties = properties;
    }

    // Reusing the interval as the initial delay too means the first sweep
    // waits a full interval after startup instead of firing immediately —
    // Spring's default for a bare fixedDelay — which keeps it well clear of
    // any test suite's runtime.
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        var interval = properties.cleanupInterval();
        registrar.scheduleFixedDelayTask(new FixedDelayTask(this::sweep, interval, interval));
    }

    void sweep() {
        tokenService.deleteExpiredOrConsumed();
        emailChangeTokenStore.deleteExpiredOrConsumed();
        rateLimitBucketStore.deleteStale();
    }
}
