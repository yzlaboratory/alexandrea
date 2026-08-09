package dev.yzlaboratory.alexandrea.auth;

import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Spring's bundled {@code sql-error-codes.xml} has no SQLite entry, so a
 * constraint violation against this app's only database translates to an
 * {@link UncategorizedSQLException} rather than the
 * {@code DataIntegrityViolationException} code elsewhere might expect from a
 * database Spring does recognise — this is the one place that unwraps it
 * correctly instead of catching (and silently never matching) the wrong
 * type. Checks the standard JDBC error code rather than the SQLite driver's
 * own exception type, since that driver is {@code runtimeOnly} and isn't on
 * this module's compile classpath.
 */
final class SqliteConstraintViolations {

    // SQLite's generic result code for every constraint family (UNIQUE, NOT
    // NULL, CHECK, FK); https://www.sqlite.org/rescode.html#constraint.
    private static final int SQLITE_CONSTRAINT = 19;

    private SqliteConstraintViolations() {}

    static boolean isConstraintViolation(UncategorizedSQLException e) {
        return e.getSQLException() != null && e.getSQLException().getErrorCode() == SQLITE_CONSTRAINT;
    }
}
