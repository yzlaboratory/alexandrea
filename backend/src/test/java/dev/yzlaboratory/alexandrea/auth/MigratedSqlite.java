package dev.yzlaboratory.alexandrea.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A throwaway SQLite database with the real Flyway schema applied, for the
 * store/token tests that exercise persistence directly.
 *
 * <p>A temp file (not {@code :memory:}) is used deliberately: a pure in-memory
 * SQLite URL gives each JDBC connection its own empty database, so Flyway's
 * migration and the test's queries would land in different DBs. The temp file
 * shares one database across connections and is deleted on JVM exit, exercising
 * the real SQLite dialect prod runs (ADR 0014).
 */
final class MigratedSqlite implements AutoCloseable {

    private final Path dbFile;
    private final DataSource dataSource;

    private MigratedSqlite(Path dbFile, DataSource dataSource) {
        this.dbFile = dbFile;
        this.dataSource = dataSource;
    }

    static MigratedSqlite create() {
        try {
            var dbFile = Files.createTempFile("alexandrea-test-", ".db");
            var url = "jdbc:sqlite:" + dbFile + "?foreign_keys=on";
            var dataSource = new DriverManagerDataSource(url);
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
            return new MigratedSqlite(dbFile, dataSource);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create temp SQLite database", e);
        }
    }

    JdbcClient jdbcClient() {
        return JdbcClient.create(dataSource);
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(dbFile);
        } catch (IOException e) {
            // Temp file cleanup is best-effort; the OS reaps it eventually.
        }
    }
}
