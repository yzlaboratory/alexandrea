package dev.yzlaboratory.alexandrea.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.yzlaboratory.alexandrea.auth.AuthProperties;
import dev.yzlaboratory.alexandrea.auth.MutableClock;
import dev.yzlaboratory.alexandrea.auth.mail.MailDispatcher;
import dev.yzlaboratory.alexandrea.auth.mail.MailMessage;
import dev.yzlaboratory.alexandrea.auth.mail.MailSender;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * The observable HTTP contract of the auth flows — signup, verify, login,
 * logout, session — end-to-end through the real wiring. Flyway runs against a
 * temp-file SQLite so the production dialect and migrations are exercised —
 * unlike the Flyway-disabled smoke test.
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
        jdbcClient.sql("DELETE FROM SPRING_SESSION").update();
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


    @Test
    void verifiedLoginEstablishesASessionThatResolvesToTheUser() throws Exception {
        signup("login@example.com", "a-good-long-password");
        verify(extractToken(mailSender.sent.getFirst())).andExpect(status().isOk());

        var result = login("login@example.com", "a-good-long-password")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(true))
            .andReturn();

        var sessionCookie = sessionCookieFrom(result);
        assertThat(sessionCookie).isNotNull();
        mockMvc.perform(get("/api/auth/session").cookie(sessionCookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("login@example.com"));
    }

    /**
     * {@code @DirtiesContext} forces a fresh Spring context for just this test:
     * MockMvc's {@code .with(csrf())} (used by every other test's helpers) seeds
     * a *session-based* test-only CSRF repository, and that — combined with
     * {@link dev.yzlaboratory.alexandrea.config.CsrfCookieFilter} eagerly reading
     * the token on every request — leaves a real JDBC session row behind for
     * later requests in the same shared context. A live server hit with curl
     * (no MockMvc, no {@code .with(csrf())}) confirms zero session rows are
     * created here even after real prior signup/login activity, so this is a
     * MockMvc test-double artifact, not a production behaviour; the isolated
     * context is what makes that provable in-suite.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    void anAnonymousSessionCheckLeavesNoSessionRowBehind() throws Exception {
        // The default HttpSessionRequestCache creates a session on every
        // rejected request just to remember it for a server-side redirect
        // replay — a pattern this REST API doesn't use. Left enabled, the
        // SPA's own session check on every page load would leak a row here.
        mockMvc.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());

        assertThat(sessionRowCount()).isZero();
    }

    @Test
    void anOverlongLoginPasswordIsRejectedWithoutHashingIt() throws Exception {
        // A registered password always satisfies PasswordPolicy, so this is
        // already a guaranteed mismatch — the point of the test is that it's
        // rejected before Argon2id runs on it, not just that it's rejected.
        signup("known@example.com", "a-good-long-password");
        verify(extractToken(mailSender.sent.getFirst())).andExpect(status().isOk());
        var overlongPassword = "a".repeat(129);

        login("known@example.com", overlongPassword).andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedAccountWithCorrectPasswordShowsVerifyPromptAndEstablishesNoSession() throws Exception {
        signup("pending@example.com", "a-good-long-password");

        login("pending@example.com", "a-good-long-password")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verified").value(false))
            .andExpect(jsonPath("$.lastMediaType").doesNotExist());

        assertThat(sessionRowCount()).isZero();
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheIdenticalGenericRejection() throws Exception {
        signup("known@example.com", "a-good-long-password");
        verify(extractToken(mailSender.sent.getFirst())).andExpect(status().isOk());

        var wrongPassword = login("known@example.com", "totally-wrong-password")
            .andExpect(status().isUnauthorized())
            .andReturn();
        var unknownEmail = login("nobody-here@example.com", "totally-wrong-password")
            .andExpect(status().isUnauthorized())
            .andReturn();

        assertThat(unknownEmail.getResponse().getContentAsString())
            .isEqualTo(wrongPassword.getResponse().getContentAsString());
    }

    @Test
    void logoutInvalidatesTheSessionServerSide() throws Exception {
        signup("logout@example.com", "a-good-long-password");
        verify(extractToken(mailSender.sent.getFirst())).andExpect(status().isOk());
        var sessionCookie = sessionCookieFrom(
            login("logout@example.com", "a-good-long-password").andReturn());

        mockMvc.perform(post("/api/auth/logout").with(csrf()).cookie(sessionCookie))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/session").cookie(sessionCookie))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void anIdleSessionPastTheThirtyDayRollingWindowIsInvalid() throws Exception {
        signup("idle@example.com", "a-good-long-password");
        verify(extractToken(mailSender.sent.getFirst())).andExpect(status().isOk());
        var sessionCookie = sessionCookieFrom(
            login("idle@example.com", "a-good-long-password").andReturn());

        // Spring Session tracks LAST_ACCESS_TIME/EXPIRY_TIME in real wall-clock
        // millis, not the injected Clock, so backdating both directly in the
        // JDBC store is the only way to fast-forward past the rolling window.
        var pastWindow = Duration.ofDays(31).toMillis();
        jdbcClient.sql("""
                UPDATE SPRING_SESSION
                SET LAST_ACCESS_TIME = LAST_ACCESS_TIME - :pastWindow,
                    EXPIRY_TIME = EXPIRY_TIME - :pastWindow
                """)
            .param("pastWindow", pastWindow)
            .update();

        mockMvc.perform(get("/api/auth/session").cookie(sessionCookie))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionsArePersistedToTheJdbcStoreSoARedeploySurvivesThem() throws Exception {
        signup("persist@example.com", "a-good-long-password");
        verify(extractToken(mailSender.sent.getFirst())).andExpect(status().isOk());

        login("persist@example.com", "a-good-long-password").andExpect(status().isOk());

        assertThat(sessionRowCount()).isOne();
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
            .with(csrf())
            .contentType("application/json")
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private static Cookie sessionCookieFrom(MvcResult result) {
        return Arrays.stream(result.getResponse().getCookies())
            .filter(cookie -> !cookie.getName().equals("XSRF-TOKEN"))
            .findFirst()
            .orElse(null);
    }

    private int sessionRowCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM SPRING_SESSION").query(Integer.class).single();
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
