package dev.yzlaboratory.alexandrea.surface;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yzlaboratory.alexandrea.auth.MigratedSqlite;
import dev.yzlaboratory.alexandrea.auth.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * The shared preference store's get/upsert round-trip against a real
 * Flyway-migrated SQLite, mirroring {@code EmailChangeTokenStoreTest}'s
 * shape — {@code surface_preferences} is keyed {@code (user_id, surface,
 * media_type)} (ADR 0025), and this store performs no validation of the
 * {@code filters} column's content.
 */
class SurfacePreferenceStoreTest {

    private static final Instant START = Instant.parse("2026-06-07T12:00:00Z");
    private static final String CATALOG_SURFACE = "catalog";
    private static final String MOVIES = "movies";
    private static final String BOOKS = "books";

    private MigratedSqlite db;
    private MutableClock clock;
    private SurfacePreferenceStore store;
    private long userId;

    @BeforeEach
    void setUp() {
        db = MigratedSqlite.create();
        clock = new MutableClock(START);
        store = new SurfacePreferenceStore(db.jdbcClient(), clock);
        userId = insertUser(db.jdbcClient(), "reader@example.com");
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void getOnAnUnknownKeyReturnsEmptyRatherThanThrowing() {
        assertThat(store.get(userId, CATALOG_SURFACE, MOVIES)).isEmpty();
    }

    @Test
    void upsertThenGetRoundTripsTheSortColumns() {
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);

        var preference = store.get(userId, CATALOG_SURFACE, MOVIES);

        assertThat(preference).isPresent();
        assertThat(preference.get().userId()).isEqualTo(userId);
        assertThat(preference.get().surface()).isEqualTo(CATALOG_SURFACE);
        assertThat(preference.get().mediaType()).isEqualTo(MOVIES);
        assertThat(preference.get().sortKey()).isEqualTo("popularity");
        assertThat(preference.get().sortDirection()).isEqualTo("desc");
    }

    @Test
    void aSecondUpsertForTheSameKeyReplacesRatherThanDuplicating() {
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);

        store.upsert(userId, CATALOG_SURFACE, MOVIES, "title", "asc", null);

        var preference = store.get(userId, CATALOG_SURFACE, MOVIES);
        assertThat(preference.get().sortKey()).isEqualTo("title");
        assertThat(preference.get().sortDirection()).isEqualTo("asc");
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void anUpsertRecordsTheCurrentClockTimeAsUpdatedAt() {
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);

        assertThat(store.get(userId, CATALOG_SURFACE, MOVIES).get().updatedAt()).isEqualTo(START);
    }

    @Test
    void aLaterUpsertAdvancesUpdatedAt() {
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);
        clock.advance(java.time.Duration.ofHours(1));

        store.upsert(userId, CATALOG_SURFACE, MOVIES, "title", "asc", null);

        assertThat(store.get(userId, CATALOG_SURFACE, MOVIES).get().updatedAt())
            .isEqualTo(START.plus(java.time.Duration.ofHours(1)));
    }

    @Test
    void aNullFiltersColumnRoundTripsAsNullRatherThanBlowingUp() {
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);

        var preference = store.get(userId, CATALOG_SURFACE, MOVIES);

        assertThat(preference.get().filters()).isNull();
    }

    @Test
    void anOpaqueFiltersBlobIsStoredAndReturnedVerbatimWithoutValidation() {
        var arbitraryJson = "{\"totally\": [\"unvalidated\", 1, true]}";

        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", arbitraryJson);

        assertThat(store.get(userId, CATALOG_SURFACE, MOVIES).get().filters()).isEqualTo(arbitraryJson);
    }

    @Test
    void differentMediaTypesForTheSameUserAndSurfaceAreIndependentRows() {
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);
        store.upsert(userId, CATALOG_SURFACE, BOOKS, "external_rating", "desc", null);

        assertThat(store.get(userId, CATALOG_SURFACE, MOVIES).get().sortKey()).isEqualTo("popularity");
        assertThat(store.get(userId, CATALOG_SURFACE, BOOKS).get().sortKey()).isEqualTo("external_rating");
    }

    @Test
    void differentSurfacesForTheSameUserAndMediaTypeAreIndependentRows() {
        store.upsert(userId, "catalog", MOVIES, "popularity", "desc", null);
        store.upsert(userId, "watchlist", MOVIES, "title", "asc", null);

        assertThat(store.get(userId, "catalog", MOVIES).get().sortKey()).isEqualTo("popularity");
        assertThat(store.get(userId, "watchlist", MOVIES).get().sortKey()).isEqualTo("title");
    }

    @Test
    void oneUsersPreferenceDoesNotLeakIntoAnotherUsersReadForTheSameKey() {
        var otherUserId = insertUser(db.jdbcClient(), "other@example.com");
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);

        assertThat(store.get(otherUserId, CATALOG_SURFACE, MOVIES)).isEmpty();
    }

    @Test
    void deletingTheOwningUserCascadesToTheirPreferenceRows() {
        store.upsert(userId, CATALOG_SURFACE, MOVIES, "popularity", "desc", null);

        db.jdbcClient().sql("DELETE FROM users WHERE id = :id").param("id", userId).update();

        assertThat(rowCount()).isZero();
    }

    private int rowCount() {
        return db.jdbcClient().sql("SELECT COUNT(*) FROM surface_preferences").query(Integer.class).single();
    }

    private static long insertUser(JdbcClient jdbcClient, String email) {
        var keyHolder = new GeneratedKeyHolder();
        jdbcClient
            .sql("""
                INSERT INTO users (email, password_hash, verified, created_at, updated_at)
                VALUES (:email, 'hash', 0, :now, :now)
                """)
            .param("email", email)
            .param("now", START.toString())
            .update(keyHolder);
        var key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert of user did not return a generated id");
        }
        return key.longValue();
    }
}
