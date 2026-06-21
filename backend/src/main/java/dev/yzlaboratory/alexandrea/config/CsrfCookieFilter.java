package dev.yzlaboratory.alexandrea.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the CSRF token to resolve on every request so its cookie is always
 * written to the response.
 *
 * <p>Spring Security loads the CSRF token lazily: without this, the
 * {@code XSRF-TOKEN} cookie is only set once something reads the token. The SPA
 * is served as static files, so on a cold load it would make its first
 * state-changing POST with no token and be rejected. Reading
 * {@link CsrfToken#getToken()} here primes the cookie on a prior GET.
 */
class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        var csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
