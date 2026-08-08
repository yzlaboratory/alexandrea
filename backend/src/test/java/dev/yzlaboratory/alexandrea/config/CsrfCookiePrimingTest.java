package dev.yzlaboratory.alexandrea.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves CsrfCookieFilter is actually registered in SecurityConfig's chain, not
 * just correct in isolation (CsrfCookieFilterTest builds a standalone chain by
 * hand). Kept out of AuthEndpointTest and given its own context: every test
 * there uses .with(csrf()), which injects a token as a request attribute and
 * bypasses CookieCsrfTokenRepository entirely — sharing that class's cached
 * context and MockMvc singleton would let an earlier .with(csrf()) call
 * suppress this cookie write via Spring Security Test's shared HTTP session,
 * proving nothing about the real, browser-facing priming path.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CsrfCookiePrimingTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        var dbFile = Files.createTempFile("alexandrea-csrf-priming-test-", ".db");
        registry.add("spring.datasource.url",
            () -> "jdbc:sqlite:" + dbFile + "?foreign_keys=on");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aColdRequestPrimesTheCsrfCookie() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(cookie().exists("XSRF-TOKEN"));
    }
}
