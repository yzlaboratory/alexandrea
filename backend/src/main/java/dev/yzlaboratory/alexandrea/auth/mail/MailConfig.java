package dev.yzlaboratory.alexandrea.auth.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Both clients are prod-only: there is no SES/SQS access in dev or test, and
 * the default credential chain would otherwise try (and fail) to authenticate
 * outside prod. Region and credentials provider are both spelled out rather
 * than left to the SDK's own resolution: the region because ADR 0023 fixes
 * compute to eu-central-1 regardless of where the JVM runs, the credentials
 * provider because it's otherwise not obvious the EC2 instance profile (not
 * a stored secret) is what's authenticating these calls.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class MailConfig {

    @Bean
    @Profile("prod")
    public SesV2Client sesV2Client() {
        return SesV2Client.builder()
            .region(Region.EU_CENTRAL_1)
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
    }

    @Bean
    @Profile("prod")
    public SqsClient sqsClient() {
        return SqsClient.builder()
            .region(Region.EU_CENTRAL_1)
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .build();
    }
}
