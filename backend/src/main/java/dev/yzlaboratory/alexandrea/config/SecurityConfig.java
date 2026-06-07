package dev.yzlaboratory.alexandrea.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Baseline web-security wiring for the scaffold.
 *
 * <p>This is the smallest config that lets the app boot under
 * {@code spring-boot-starter-security} while keeping the posture ADR 0021
 * commits to: session-based auth, CSRF on for state-changing requests, and
 * the actuator health probe open so CloudFront / the load balancer can hit
 * it without a credential. Auth endpoints, the user store, and the Argon2id
 * password-encoder wiring all land with the auth feature ticket (#8) — this
 * file is the place they'll be added.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // The CSRF cookie is readable by the SPA (HttpOnly=false on the cookie
        // that carries the token) so the SPA can echo it on state-changing
        // requests; the session cookie itself stays HttpOnly via the
        // server.servlet.session.cookie defaults in application.yml.
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // The signup/verify/resend endpoints are reached by callers who
                // are not yet authenticated (ADR 0021, #19), so they are public.
                // Login/session-gated surfaces land authenticated in later slices.
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        return http.build();
    }

    /**
     * {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()} returns
     * a delegating encoder whose default is Argon2id, matching ADR 0021. We
     * expose it as a bean so Spring Security's user-details services pick it
     * up automatically when the auth feature lands.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
