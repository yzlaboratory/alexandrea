package dev.yzlaboratory.alexandrea.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CatalogCacheTest {

    private static final CatalogItem ITEM =
        new CatalogItem("TMDB", "1", "movies", "Title", "cover", LocalDate.of(2024, 1, 1), 7.5, 10.0);
    private static final CatalogPageResult PAGE = new CatalogPageResult(List.of(ITEM), 1, true);

    private MutableTicker ticker;
    private CatalogCache cache;

    @BeforeEach
    void setUp() {
        ticker = new MutableTicker();
        cache = new CatalogCache(ticker);
    }

    @Test
    void aPageKeyNeverWrittenIsAMiss() {
        assertThat(cache.getPage("nope")).isEmpty();
    }

    @Test
    void anItemKeyNeverWrittenIsAMiss() {
        assertThat(cache.getItem("nope")).isEmpty();
    }

    @Test
    void aStoredPageIsAHitImmediatelyAfterWrite() {
        cache.putPage("key", PAGE);

        assertThat(cache.getPage("key")).contains(PAGE);
    }

    @Test
    void aStoredItemIsAHitImmediatelyAfterWrite() {
        var key = CatalogCache.itemKey("TMDB", "1", "movies");

        cache.putItem(key, ITEM);

        assertThat(cache.getItem(key)).contains(ITEM);
    }

    @Test
    void aPageIsStillAHitJustBeforeTheSevenDayTtlElapses() {
        cache.putPage("key", PAGE);

        ticker.advance(Duration.ofDays(7).minusSeconds(1));

        assertThat(cache.getPage("key")).contains(PAGE);
    }

    @Test
    void aPageExpiresOnceTheSevenDayTtlElapses() {
        cache.putPage("key", PAGE);

        ticker.advance(Duration.ofDays(7).plusSeconds(1));

        assertThat(cache.getPage("key")).isEmpty();
    }

    @Test
    void anItemExpiresOnceTheSevenDayTtlElapsesIndependentlyOfThePageCache() {
        var key = CatalogCache.itemKey("TMDB", "1", "movies");
        cache.putItem(key, ITEM);

        ticker.advance(Duration.ofDays(7).plusSeconds(1));

        assertThat(cache.getItem(key)).isEmpty();
    }

    @Test
    void theItemAndPageCachesAreIndependentEvenWhenTheyShareAKeyString() {
        cache.putPage("shared-key", PAGE);

        assertThat(cache.getItem("shared-key")).isEmpty();
    }

    @Test
    void aReWriteResetsThatKeysTtlClock() {
        cache.putPage("key", PAGE);
        ticker.advance(Duration.ofDays(6));

        cache.putPage("key", PAGE);
        ticker.advance(Duration.ofDays(6));

        // 12 days have passed in total, but the second write reset the clock
        // 6 days ago — still well inside a fresh 7-day window.
        assertThat(cache.getPage("key")).contains(PAGE);
    }

    @Test
    void pageKeyJoinsAllSixDimensionsWithAPipeInOrder() {
        assertThat(CatalogCache.pageKey("tmdb", "movies", "popular", "", "default", 3))
            .isEqualTo("tmdb|movies|popular||default|3");
    }

    @Test
    void itemKeyJoinsProviderExternalIdAndMediaTypeWithAPipe() {
        assertThat(CatalogCache.itemKey("tmdb", "603692", "movies")).isEqualTo("tmdb|603692|movies");
    }

    @Test
    void differentPagesOfTheSameFeedGetDifferentKeys() {
        var pageOne = CatalogCache.pageKey("tmdb", "movies", "popular", "", "default", 1);
        var pageTwo = CatalogCache.pageKey("tmdb", "movies", "popular", "", "default", 2);

        assertThat(pageOne).isNotEqualTo(pageTwo);
    }

    @Test
    void getOrComputePageComputesAndCachesOnAMiss() {
        var result = cache.getOrComputePage("key", () -> PAGE);

        assertThat(result).isEqualTo(PAGE);
        assertThat(cache.getPage("key")).contains(PAGE);
    }

    @Test
    void getOrComputePageSkipsTheComputationOnAHit() {
        cache.putPage("key", PAGE);
        var callCount = new AtomicInteger();

        var result = cache.getOrComputePage("key", () -> {
            callCount.incrementAndGet();
            return PAGE;
        });

        assertThat(result).isEqualTo(PAGE);
        assertThat(callCount).hasValue(0);
    }

    @Test
    void getOrComputePageRunsTheComputationAtMostOnceAcrossRepeatedMisses() {
        // Simulates what two concurrent requests for the same never-cached
        // page must not do: pay for two upstream calls. A caller stampede
        // is a threading concern this single-threaded test can't reproduce
        // directly, but calling the atomic get-or-compute path twice in a
        // row for a key that's now cached from the first call proves the
        // second call is a pure cache hit, not a second computation.
        var callCount = new AtomicInteger();

        cache.getOrComputePage("key", () -> {
            callCount.incrementAndGet();
            return PAGE;
        });
        cache.getOrComputePage("key", () -> {
            callCount.incrementAndGet();
            return PAGE;
        });

        assertThat(callCount).hasValue(1);
    }

    @Test
    void getOrComputePageDoesNotCacheAFailedComputation() {
        assertThatThrownBy(() -> cache.getOrComputePage("key", () -> {
            throw new RuntimeException("upstream failed");
        })).isInstanceOf(RuntimeException.class);

        assertThat(cache.getPage("key")).isEmpty();
        assertThat(cache.getPageRegardlessOfTtl("key")).isEmpty();
    }

    @Test
    void aPageKeyNeverWrittenIsAlsoAMissRegardlessOfTtl() {
        assertThat(cache.getPageRegardlessOfTtl("nope")).isEmpty();
    }

    @Test
    void anItemKeyNeverWrittenIsAlsoAMissRegardlessOfTtl() {
        assertThat(cache.getItemRegardlessOfTtl("nope")).isEmpty();
    }

    @Test
    void aPageIsStillRetrievableRegardlessOfTtlAfterItsFreshnessTtlElapses() {
        cache.putPage("key", PAGE);

        ticker.advance(Duration.ofDays(7).plusSeconds(1));

        assertThat(cache.getPage("key")).isEmpty();
        assertThat(cache.getPageRegardlessOfTtl("key")).contains(PAGE);
    }

    @Test
    void anItemIsStillRetrievableRegardlessOfTtlAfterItsFreshnessTtlElapses() {
        var key = CatalogCache.itemKey("TMDB", "1", "movies");
        cache.putItem(key, ITEM);

        ticker.advance(Duration.ofDays(7).plusSeconds(1));

        assertThat(cache.getItem(key)).isEmpty();
        assertThat(cache.getItemRegardlessOfTtl(key)).contains(ITEM);
    }

    @Test
    void aPageRegardlessOfTtlIsStillAHitJustBeforeItsLongerPhysicalBoundElapses() {
        cache.putPage("key", PAGE);

        ticker.advance(Duration.ofDays(28).minusSeconds(1));

        assertThat(cache.getPageRegardlessOfTtl("key")).contains(PAGE);
    }

    @Test
    void aPageRegardlessOfTtlIsEvictedOnceItsLongerPhysicalBoundElapses() {
        cache.putPage("key", PAGE);

        ticker.advance(Duration.ofDays(28).plusSeconds(1));

        assertThat(cache.getPageRegardlessOfTtl("key")).isEmpty();
    }

    @Test
    void getOrComputePagePopulatesTheStaleFallbackOnASuccessfulComputeToo() {
        cache.getOrComputePage("key", () -> PAGE);

        ticker.advance(Duration.ofDays(7).plusSeconds(1));

        assertThat(cache.getPage("key")).isEmpty();
        assertThat(cache.getPageRegardlessOfTtl("key")).contains(PAGE);
    }
}
