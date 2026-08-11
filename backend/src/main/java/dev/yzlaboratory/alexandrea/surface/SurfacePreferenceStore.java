package dev.yzlaboratory.alexandrea.surface;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC repository over {@code surface_preferences} (ADR 0025) — one row per
 * {@code (userId, surface, mediaType)}, shared by every Surface (Catalog,
 * Watchlist, Library, Shares). {@code upsert} replaces the whole row,
 * including {@code filters}: a caller that only means to change the sort
 * must pass along the filters it already has (e.g. from a prior {@link
 * #get}), the same "read, modify, write the whole document" contract as any
 * upsert over a shared row. Neither method inspects or validates {@code
 * filters} — it is opaque JSON the calling surface alone understands.
 */
@Repository
public class SurfacePreferenceStore {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public SurfacePreferenceStore(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    public Optional<SurfacePreference> get(long userId, String surface, String mediaType) {
        return jdbcClient
            .sql("SELECT * FROM surface_preferences WHERE user_id = :userId AND surface = :surface AND media_type = :mediaType")
            .param("userId", userId)
            .param("surface", surface)
            .param("mediaType", mediaType)
            .query(SurfacePreferenceStore::mapRow)
            .optional();
    }

    public void upsert(
        long userId, String surface, String mediaType, String sortKey, String sortDirection, String filters
    ) {
        jdbcClient
            .sql("""
                INSERT INTO surface_preferences (user_id, surface, media_type, sort_key, sort_direction, filters, updated_at)
                VALUES (:userId, :surface, :mediaType, :sortKey, :sortDirection, :filters, :now)
                ON CONFLICT(user_id, surface, media_type) DO UPDATE SET
                    sort_key = excluded.sort_key,
                    sort_direction = excluded.sort_direction,
                    filters = excluded.filters,
                    updated_at = excluded.updated_at
                """)
            .param("userId", userId)
            .param("surface", surface)
            .param("mediaType", mediaType)
            .param("sortKey", sortKey)
            .param("sortDirection", sortDirection)
            .param("filters", filters)
            .param("now", clock.instant().toString())
            .update();
    }

    private static SurfacePreference mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new SurfacePreference(
            rs.getLong("user_id"),
            rs.getString("surface"),
            rs.getString("media_type"),
            rs.getString("sort_key"),
            rs.getString("sort_direction"),
            rs.getString("filters"),
            Instant.parse(rs.getString("updated_at"))
        );
    }
}
