package dev.yzlaboratory.alexandrea.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Web-security wiring for the posture ADR 0021 commits to: session-based auth,
 * CSRF on for state-changing requests, the actuator health probe open so
 * CloudFront / the load balancer can hit it without a credential, and the
 * unauthenticated auth endpoints (signup / verify / resend) public.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // The CSRF cookie is readable by the SPA (HttpOnly=false on the cookie
        // that carries the token) so the SPA can echo it on state-changing
        // requests; the session cookie itself stays HttpOnly via the
        // server.servlet.session.cookie defaults in application.yml. The plain
        // request handler (not the XOR default) expects the raw cookie value in
        // the header — the value the SPA reads back — and CsrfCookieFilter primes
        // the cookie so the first POST after a cold load already carries it.
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
        return http.build();
    }

    /**
     * {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()} returns
     * a delegating encoder whose default is Argon2id, matching ADR 0021.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
