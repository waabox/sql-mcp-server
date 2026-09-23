package co.fanki.sqlmcp.connection.domain;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating and caching DataSource instances from connection profiles.
 *
 * <p>Uses HikariCP for connection pooling. DataSources are cached by profile name
 * to avoid creating multiple pools for the same connection.
 *
 * <p>Every pool is read-only and manual-commit, regardless of the profile
 * configuration. Queries must run through {@link #openSession}, which applies
 * the server-side timeouts and always rolls back.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class DataSourceFactory {

    private static final int DEFAULT_POOL_SIZE = 5;
    private static final int DEFAULT_MIN_IDLE = 1;
    private static final long DEFAULT_CONNECTION_TIMEOUT_MS = 30_000;
    private static final long DEFAULT_IDLE_TIMEOUT_MS = 600_000;
    private static final long DEFAULT_MAX_LIFETIME_MS = 1_800_000;
    private static final String APPLICATION_NAME = "sql-mcp-server";

    private final Map<String, HikariDataSource> dataSources;
    private final ConnectionProfileRepository profileRepository;
    private final long lockTimeoutMs;

    /**
     * Creates a new DataSourceFactory.
     *
     * @param profileRepository the repository for connection profiles
     * @param lockTimeoutMs the maximum time a query may wait for a lock, in milliseconds
     */
    public DataSourceFactory(
            final ConnectionProfileRepository profileRepository,
            @Value("${sql-mcp.query.lock-timeout-ms:5000}") final long lockTimeoutMs) {
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.dataSources = new ConcurrentHashMap<>();
        this.lockTimeoutMs = lockTimeoutMs > 0 ? lockTimeoutMs : 5000;
    }

    /**
     * Gets or creates a DataSource for the given connection profile name.
     *
     * @param profileName the name of the connection profile
     * @return the DataSource for this profile
     * @throws IllegalArgumentException if no profile exists with this name
     */
    public DataSource getDataSource(final String profileName) {
        return dataSources.computeIfAbsent(profileName, this::createDataSource);
    }

    /**
     * Opens a read-only session for a single unit of work.
     *
     * <p>Business rules: the session runs in a read-only transaction with a
     * server-side statement timeout and lock timeout, and is always rolled back
     * on close. Callers must close it (try-with-resources).
     *
     * @param profileName the name of the connection profile
     * @param statementTimeoutMs the server-side statement timeout in milliseconds
     * @return the open session
     * @throws IllegalArgumentException if no profile exists with this name
     * @throws SQLException if a connection cannot be obtained or prepared
     */
    public ReadOnlySession openSession(final String profileName, final long statementTimeoutMs)
            throws SQLException {
        ConnectionProfile profile = profileRepository.findByName(profileName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Connection profile not found: " + profileName));
        Connection connection = getDataSource(profileName).getConnection();
        return ReadOnlySession.open(connection, profile.type(), statementTimeoutMs, lockTimeoutMs);
    }

    /**
     * Tests connectivity to the database using the given profile.
     *
     * @param profileName the name of the connection profile
     * @return a TestResult indicating success or failure
     */
    public TestResult testConnection(final String profileName) {
        ConnectionProfile profile = profileRepository.findByName(profileName)
                .orElse(null);

        if (profile == null) {
            return TestResult.failure("Connection profile not found: " + profileName);
        }

        try {
            DataSource dataSource = getDataSource(profileName);
            try (Connection connection = dataSource.getConnection()) {
                boolean valid = connection.isValid(5);
                if (valid) {
                    String info = String.format(
                            "Connected to %s (%s)",
                            connection.getCatalog(),
                            connection.getMetaData().getDatabaseProductVersion()
                    );
                    return TestResult.success(info);
                } else {
                    return TestResult.failure("Connection validation failed");
                }
            }
        } catch (SQLException e) {
            return TestResult.failure("Connection failed: " + e.getMessage());
        }
    }

    /**
     * Closes all cached DataSource connections.
     *
     * <p>Called automatically when the application shuts down.
     */
    @PreDestroy
    public void closeAll() {
        dataSources.values().forEach(HikariDataSource::close);
        dataSources.clear();
    }

    private HikariDataSource createDataSource(final String profileName) {
        ConnectionProfile profile = profileRepository.findByName(profileName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Connection profile not found: " + profileName));

        HikariConfig config = new HikariConfig();
        config.setPoolName("sql-mcp-" + profileName);
        config.setJdbcUrl(profile.jdbcUrl());
        config.setDriverClassName(profile.type().driverClassName());

        if (profile.username() != null) {
            config.setUsername(profile.username());
        }
        if (profile.password() != null) {
            config.setPassword(profile.password());
        }
        if (profile.schema() != null) {
            config.setSchema(profile.schema());
        }

        // Always read-only and manual-commit: with auto-commit on, the PostgreSQL
        // driver does not enforce read-only mode and ignores the fetch size.
        config.setReadOnly(true);
        config.setAutoCommit(false);
        switch (profile.type()) {
            case POSTGRESQL -> config.addDataSourceProperty("ApplicationName", APPLICATION_NAME);
            case MYSQL, MARIADB -> config.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
            case SQLITE -> config.addDataSourceProperty("open_mode", "1");
        }
        config.setMaximumPoolSize(DEFAULT_POOL_SIZE);
        config.setMinimumIdle(DEFAULT_MIN_IDLE);
        config.setConnectionTimeout(DEFAULT_CONNECTION_TIMEOUT_MS);
        config.setIdleTimeout(DEFAULT_IDLE_TIMEOUT_MS);
        config.setMaxLifetime(DEFAULT_MAX_LIFETIME_MS);

        return new HikariDataSource(config);
    }

    /**
     * Result of a connection test operation.
     */
    public static final class TestResult {

        private final boolean success;
        private final String message;

        private TestResult(final boolean success, final String message) {
            this.success = success;
            this.message = message;
        }

        /**
         * Creates a successful test result.
         *
         * @param message the success message with connection info
         * @return a success result
         */
        public static TestResult success(final String message) {
            return new TestResult(true, message);
        }

        /**
         * Creates a failed test result.
         *
         * @param message the error message
         * @return a failure result
         */
        public static TestResult failure(final String message) {
            return new TestResult(false, message);
        }

        /**
         * Returns whether the test was successful.
         *
         * @return true if the connection test passed
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * Returns the result message.
         *
         * @return the success info or error message
         */
        public String message() {
            return message;
        }

    }

}
