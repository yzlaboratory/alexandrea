package dev.yzlaboratory.alexandrea.auth.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Unlike {@link dev.yzlaboratory.alexandrea.auth.AuthProperties}, these two
 * fields have no compact-constructor fallback: both are AWS-account-specific
 * Terraform outputs (ADR 0023) with no value that would be safe to send with
 * by default. {@link SesMailSender} and {@link SesEventsConsumer} — the only
 * readers, both {@code @Profile("prod")} — fail fast in their constructors if
 * either is blank, rather than silently sending without a configuration set.
 */
@ConfigurationProperties(prefix = "alexandrea.mail")
public record MailProperties(String sesConfigurationSet, String sesEventsQueueUrl) {}
