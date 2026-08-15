package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CatalogConfigTest {

    @Test
    void connectAndReadTimeoutsAreFiniteRatherThanDefaultingToWaitingForever() {
        // A regression guard for a bug where none of the provider RestClient
        // beans configured a timeout at all: a provider that connects but
        // never responds would hang the calling thread indefinitely, and
        // ProviderCircuitBreaker never trips on a hang, only on an actual
        // failure. This doesn't pin the exact durations (a judgement call,
        // not an ADR-mandated one) — just that they're set and finite.
        var settings = new CatalogConfig().catalogHttpClientSettings();

        assertThat(settings.connectTimeout()).isNotNull().isPositive();
        assertThat(settings.readTimeout()).isNotNull().isPositive();
    }

    @Test
    void connectTimeoutIsShorterThanReadTimeout() {
        // A sanity check on the two chosen values themselves: connecting is
        // expected to be fast (or fail fast) while a real response can
        // reasonably take longer, so connect should never exceed read.
        assertThat(CatalogConfig.CONNECT_TIMEOUT).isLessThan(CatalogConfig.READ_TIMEOUT);
    }
}
