package dev.yzlaboratory.alexandrea.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Driven through the real {@link CsrfFilter} so the deferred-token wiring is the
 * production one.
 */
class CsrfCookieFilterTest {

    @Test
    void writesAReadableCsrfCookieWhenTheRequestCarriesNone() throws Exception {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        var csrfFilter = new CsrfFilter(repository);
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());

        var request = new MockHttpServletRequest("GET", "/");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain(noOpServlet(), new CsrfCookieFilter());

        csrfFilter.doFilter(request, response, chain);

        var cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    private static HttpServlet noOpServlet() {
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) {
                // The chain's terminal: the filter under test already ran before it.
            }
        };
    }
}
