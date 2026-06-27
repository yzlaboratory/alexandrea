package dev.yzlaboratory.alexandrea.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.yzlaboratory.alexandrea.auth.AuthProperties;
import dev.yzlaboratory.alexandrea.auth.MutableClock;
import dev.yzlaboratory.alexandrea.auth.mail.MailDispatcher;
import dev.yzlaboratory.alexandrea.auth.mail.MailMessage;
import dev.yzlaboratory.alexandrea.auth.mail.MailSender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * The observable HTTP contract of the signup → verify slice, end-to-end through
 * the real wiring. Flyway runs against a temp-file SQLite so the production
 * dialect and migrations are exercised — unlike the Flyway-disabled smoke test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthEndpointTest {

    private static Path dbFile;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        // One temp DB shared across the pool's connections; a pure :memory: URL
        // would give each connection its own empty database.
        dbFile = Files.createTempFile("alexandrea-endpoint-test-", ".db");
        registry.add("spring.datasource.url",
            () -> "jdbc:sqlite:" + dbFile + "?foreign_keys=on");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CapturingMailSender mailSender;

    @Autowired
    private MutableClock clock;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void resetState() {
        mailSender.sent.clear();
        clock.resetTo(Instant.parse("2026-06-07T12:00:00Z"));
        jdbcClient.sql("DELETE FROM auth_tokens").update();
        jdbcClient.sql("DELETE FROM users").update();
    }

    @Test
    void signupCreatesUnverifiedAccountAndMailsTheLinkToThatAddress() throws Exception {
        signup("newcomer@example.com", "a-good-long-password");

        assertThat(verifiedFlagFor("newcomer@example.com")).isZero();
        assertThat(mailSender.sent).hasSize(1);
        assertThat(mailSender.sent.getFirst().to()).isEqualTo("newcomer@example.com");
        assertThat(extractToken(mailSender.sent.getFirst())).isNotBlank();
    }

    @Test
    void openingTheVerificationLinkMarksTheAccountVerified() throws Exception {
        signup("newcomer@example.com", "a-good-long-password");
        var token = extractToken(mailSender.sent.getFirst());

        verify(token)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true));

        assertThat(verifiedFlagFor("newcomer@example.com")).isOne();
    }

    @Test
    void anExpiredLinkIsRejectedWithAResendOffer() throws Exception {
        signup("newcomer@example.com", "a-good-long-password");
        var token = extractToken(mailSender.sent.getFirst());
        clock.advance(Duration.ofHours(24).plusMinutes(1));

        verify(token)
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.canResend").value(true));

        assertThat(verifiedFlagFor("newcomer@example.com")).isZero();
    }

    @Test
    void anAlreadyUsedLinkIsRejectedWithAResendOffer() throws Exception {
        signup("newcomer@example.com", "a-good-long-password");
        var token = extractToken(mailSender.sent.getFirst());
        verify(token).andExpect(status().isOk());

        verify(token)
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.canResend").value(true));
    }

    @Test
    void resendMailsAFreshLinkAndInvalidatesThePrior() throws Exception {
        signup("newcomer@example.com", "a-good-long-password");
        var firstToken = extractToken(mailSender.sent.getFirst());

        mockMvc.perform(post("/api/auth/resend")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"newcomer@example.com\"}"))
            .andExpect(status().isAccepted());

        assertThat(mailSender.sent).hasSize(2);
        var secondToken = extractToken(mailSender.sent.getLast());
        verify(firstToken).andExpect(status().isGone());
        verify(secondToken).andExpect(status().isOk());
    }

    @Test
    void aDuplicateSignupIsIndistinguishableFromAFreshOne() throws Exception {
        signup("dup@example.com", "a-good-long-password");
        // Same address again: still 202, and the existing account is untouched
        // (no enumeration signal). This slice does not re-send for duplicates.
        signup("dup@example.com", "another-long-password");

        assertThat(mailSender.sent).hasSize(1);
    }

    @Test
    void aTooShortPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"short@example.com\",\"password\":\"tooshort\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type").value("urn:alexandrea:auth:password-policy"));

        assertThat(mailSender.sent).isEmpty();
    }

    @Test
    void aTooLongPasswordIsRejected() throws Exception {
        var longPassword = "a".repeat(129);
        mockMvc.perform(post("/api/auth/signup")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"long@example.com\",\"password\":\"" + longPassword + "\"}"))
            .andExpect(status().isBadRequest());

        assertThat(mailSender.sent).isEmpty();
    }

    @Test
    void passwordLengthIsMeasuredInCodePointsAndRejectionStays400() throws Exception {
        // Seven game-controller emoji are 14 UTF-16 chars but only 7 code points:
        // a UTF-16 length check would wave them through, the code-point policy
        // must reject them — cleanly as 400, never an unhandled 500.
        var emoji = "🎮";
        mockMvc.perform(post("/api/auth/signup")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"emoji@example.com\",\"password\":\"" + emoji.repeat(7) + "\"}"))
            .andExpect(status().isBadRequest());
        assertThat(mailSender.sent).isEmpty();

        // Twelve of the same emoji are 12 code points (24 UTF-16 chars) — within
        // policy, so they are accepted rather than wrongly rejected by a char cap.
        signup("emoji@example.com", emoji.repeat(12));
        assertThat(mailSender.sent).hasSize(1);
    }

    @Test
    void resendToAVerifiedAccountSendsNoMail() throws Exception {
        signup("verified@example.com", "a-good-long-password");
        verify(extractToken(mailSender.sent.getFirst())).andExpect(status().isOk());
        mailSender.sent.clear();

        mockMvc.perform(post("/api/auth/resend")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"verified@example.com\"}"))
            .andExpect(status().isAccepted());

        assertThat(mailSender.sent).isEmpty();
    }


    private void signup(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                .with(csrf())
                .contentType("application/json")
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isAccepted());
    }

    private org.springframework.test.web.servlet.ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/verify")
            .with(csrf())
            .contentType("application/json")
            .content("{\"token\":\"" + token + "\"}"));
    }

    private int verifiedFlagFor(String email) {
        return jdbcClient
            .sql("SELECT verified FROM users WHERE email = :email")
            .param("email", email)
            .query(Integer.class)
            .single();
    }

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private static String extractToken(MailMessage message) {
        var matcher = TOKEN_IN_LINK.matcher(message.body());
        if (!matcher.find()) {
            throw new IllegalStateException("No verification token in mail body:\n" + message.body());
        }
        return matcher.group(1);
    }

    @TestConfiguration
    static class TestBeans {

        /** Captures sent mail so tests assert recipient and link without SES. */
        @Bean
        @Primary
        CapturingMailSender capturingMailSender() {
            return new CapturingMailSender();
        }

        /**
         * A synchronous dispatcher so a sent mail is captured before the request
         * returns. Production dispatches off the request thread (MailDispatcher),
         * which is what keeps response latency from leaking account state — but
         * that would race these assertions.
         */
        @Bean
        @Primary
        MailDispatcher synchronousMailDispatcher(MailSender mailSender) {
            return new MailDispatcher(mailSender, Runnable::run);
        }

        /** A clock the expiry test can fast-forward, replacing the system clock. */
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-06-07T12:00:00Z"));
        }

        /** Bind a deterministic verification URL so token extraction is stable. */
        @Bean
        @Primary
        AuthProperties testAuthProperties() {
            return new AuthProperties(Duration.ofHours(24), "http://localhost/verify?token={token}");
        }
    }

    /** A {@link MailSender} that records every message instead of delivering it. */
    static class CapturingMailSender implements MailSender {
        final List<MailMessage> sent = new ArrayList<>();

        @Override
        public void send(MailMessage message) {
            sent.add(message);
        }
    }
}
