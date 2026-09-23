package co.fanki.sqlmcp.connection.domain;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * A pooled connection prepared for a single read-only unit of work.
 *
 * <p>When opened, the session applies the engine-specific server-side guards
 * (read-only transaction, statement timeout, lock timeout). When closed, the
 * transaction is always rolled back before the connection returns to the pool,
 * so nothing a query does can outlive the session.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class ReadOnlySession implements AutoCloseable {

    private final Connection connection;

    private ReadOnlySession(final Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens a read-only session on an already acquired connection.
     *
     * <p>Business rules: the connection must be in manual-commit, read-only mode
     * (enforced by the pool configuration). For PostgreSQL the timeouts are set
     * with {@code SET LOCAL}, so they only live for this transaction and work
     * behind transaction-pooling proxies such as PgBouncer. For MySQL/MariaDB the
     * statement timeout is set per session before every unit of work.
     *
     * @param connection the pooled connection, never null
     * @param type the database engine, never null
     * @param statementTimeoutMs server-side statement timeout in milliseconds
     * @param lockTimeoutMs server-side lock wait timeout in milliseconds
     * @return the prepared session
     * @throws SQLException if the guards cannot be applied; the connection is closed in that case
     */
    static ReadOnlySession open(
            final Connection connection,
            final DatabaseType type,
            final long statementTimeoutMs,
            final long lockTimeoutMs) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(type, "type must not be null");
        try {
            if (connection.getAutoCommit()) {
                connection.setAutoCommit(false);
            }
            if (!connection.isReadOnly()) {
                connection.setReadOnly(true);
            }
            try (Statement statement = connection.createStatement()) {
                switch (type) {
                    case POSTGRESQL -> {
                        statement.execute("SET LOCAL statement_timeout = " + statementTimeoutMs);
                        statement.execute("SET LOCAL lock_timeout = " + lockTimeoutMs);
                    }
                    case MYSQL -> {
                        statement.execute("SET SESSION max_execution_time = " + statementTimeoutMs);
                        statement.execute("SET SESSION innodb_lock_wait_timeout = " + toSeconds(lockTimeoutMs));
                    }
                    case MARIADB -> {
                        statement.execute("SET SESSION max_statement_time = " + toSeconds(statementTimeoutMs));
                        statement.execute("SET SESSION innodb_lock_wait_timeout = " + toSeconds(lockTimeoutMs));
                    }
                    case SQLITE -> {
                        // Read-only is enforced by opening the file in read-only mode.
                    }
                }
            }
            return new ReadOnlySession(connection);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    private static long toSeconds(final long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    /**
     * Returns the underlying connection.
     *
     * @return the connection bound to this session
     */
    public Connection connection() {
        return connection;
    }

    /**
     * Rolls back the transaction and returns the connection to the pool.
     *
     * @throws SQLException if the connection cannot be closed
     */
    @Override
    public void close() throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection may already be broken (e.g. after a timeout); closing is what matters.
        } finally {
            connection.close();
        }
    }

    private static void closeQuietly(final Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }

}
