package dev.yzlaboratory.alexandrea.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
        HttpSecurity http,
        SecurityContextRepository securityContextRepository
    ) {
        // The CSRF cookie is readable by the SPA (HttpOnly=false on the cookie
        // that carries the token) so the SPA can echo it on state-changing
        // requests. The plain request handler (not the XOR default) expects the
        // raw cookie value in the header — the value the SPA reads back.
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Ahead of the broad /api/auth/** permitAll below: matchers are
                // evaluated in order, so these must claim authenticated first
                // or the broader rule would swallow them.
                .requestMatchers(
                    "/api/auth/session", "/api/auth/media-type",
                    "/api/auth/change-password", "/api/auth/change-email")
                    .authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            // The default HttpSessionRequestCache creates a session on every
            // rejected request purely to remember it for a server-side
            // redirect replay after login — a pattern this REST API doesn't
            // use (the SPA's own RequireAuth already tracks where to return
            // to). Left enabled, every anonymous hit to a protected endpoint
            // (e.g. the SPA's own session check on load) would leak a
            // throwaway row into SPRING_SESSION.
            .requestCache(RequestCacheConfigurer::disable)
            // No formLogin/httpBasic is configured (login is a custom REST
            // endpoint), so without this an unauthenticated API request would
            // fall through to Spring Security's 403 default instead of 401.
            .exceptionHandling(handling -> handling
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(401)));
        return http.build();
    }

    /**
     * Exposed as a bean (Spring Security doesn't publish one by default) so
     * {@code AuthController} can persist the context it builds by hand when a
     * login succeeds — there's no {@code AuthenticationManager} in this app's
     * login flow to do it via the usual filter chain (see ADR 0024's login
     * ordering, which the default provider chain can't express).
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * {@link org.springframework.security.crypto.factory.PasswordEncoderFactories#createDelegatingPasswordEncoder()}
     * defaults the *encoding* id to bcrypt, not argon2 — silently contradicting
     * ADR 0021. Built explicitly here so new hashes are actually Argon2id;
     * bcrypt stays registered so already-issued hashes still verify.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        var encoders = Map.<String, PasswordEncoder>of(
            "argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
            "bcrypt", new BCryptPasswordEncoder()
        );
        return new DelegatingPasswordEncoder("argon2", encoders);
    }
}
