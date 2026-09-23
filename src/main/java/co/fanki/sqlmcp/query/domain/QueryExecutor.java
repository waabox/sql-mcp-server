package co.fanki.sqlmcp.query.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import co.fanki.sqlmcp.connection.domain.DataSourceFactory;
import co.fanki.sqlmcp.connection.domain.DatabaseType;
import co.fanki.sqlmcp.connection.domain.ReadOnlySession;
import co.fanki.sqlmcp.observability.domain.QueryAuditLog.QueryType;
import co.fanki.sqlmcp.observability.domain.QueryLogger;
import co.fanki.sqlmcp.query.domain.QueryGuard.ValidationResult;
import co.fanki.sqlmcp.query.domain.QueryResult.ColumnInfo;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Domain service for executing SQL queries with safety controls.
 *
 * <p>Provides safe query execution with:
 * <ul>
 *   <li>Query validation via {@link QueryGuard}</li>
 *   <li>A read-only session with server-side statement and lock timeouts</li>
 *   <li>Row limits enforced by the driver, so the server stops producing rows
 *       once the limit is reached</li>
 *   <li>An audit log entry for every attempt</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp.query")
public class QueryExecutor {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int MAX_TIMEOUT_MS = 120_000;
    private static final int MIN_TIMEOUT_MS = 1_000;
    private static final int DEFAULT_ROW_LIMIT = 1000;
    private static final int MAX_ROW_LIMIT = 10_000;
    private static final int MAX_FETCH_SIZE = 1000;

    private final DataSourceFactory dataSourceFactory;
    private final ConnectionProfileRepository profileRepository;
    private final QueryGuard queryGuard;
    private final QueryLogger queryLogger;

    private int defaultTimeoutMs = DEFAULT_TIMEOUT_MS;
    private int maxTimeoutMs = MAX_TIMEOUT_MS;
    private int defaultRowLimit = DEFAULT_ROW_LIMIT;
    private int maxRowLimit = MAX_ROW_LIMIT;

    /**
     * Creates a new QueryExecutor.
     *
     * @param dataSourceFactory the factory for data sources
     * @param profileRepository the connection profile repository
     * @param queryGuard the query validation service
     * @param queryLogger the audit logger
     */
    public QueryExecutor(
            final DataSourceFactory dataSourceFactory,
            final ConnectionProfileRepository profileRepository,
            final QueryGuard queryGuard,
            final QueryLogger queryLogger) {
        this.dataSourceFactory = Objects.requireNonNull(dataSourceFactory);
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.queryGuard = Objects.requireNonNull(queryGuard);
        this.queryLogger = Objects.requireNonNull(queryLogger);
    }

    /**
     * Sets the default query timeout from configuration.
     *
     * @param defaultTimeoutMs timeout in milliseconds
     */
    public void setDefaultTimeoutMs(final int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs > 0 ? defaultTimeoutMs : DEFAULT_TIMEOUT_MS;
    }

    /**
     * Sets the maximum query timeout a caller may request, from configuration.
     *
     * @param maxTimeoutMs maximum timeout in milliseconds
     */
    public void setMaxTimeoutMs(final int maxTimeoutMs) {
        this.maxTimeoutMs = maxTimeoutMs > 0 ? maxTimeoutMs : MAX_TIMEOUT_MS;
    }

    /**
     * Sets the default row limit from configuration.
     *
     * @param defaultRowLimit default number of rows to return
     */
    public void setDefaultRowLimit(final int defaultRowLimit) {
        this.defaultRowLimit = defaultRowLimit > 0 ? defaultRowLimit : DEFAULT_ROW_LIMIT;
    }

    /**
     * Sets the maximum row limit from configuration.
     *
     * @param maxRowLimit maximum number of rows allowed
     */
    public void setMaxRowLimit(final int maxRowLimit) {
        this.maxRowLimit = maxRowLimit > 0 ? maxRowLimit : MAX_ROW_LIMIT;
    }

    /**
     * Executes a SQL query with default settings.
     *
     * @param connectionName the connection profile name
     * @param query the SQL query to execute
     * @return the query execution result
     * @throws QueryExecutionException if validation or execution fails
     * @throws IllegalArgumentException if the connection profile does not exist
     */
    public QueryResult execute(final String connectionName, final String query) {
        return execute(connectionName, query, defaultRowLimit, defaultTimeoutMs);
    }

    /**
     * Executes a SQL query with custom settings.
     *
     * <p>Business rules: the query must pass {@link QueryGuard}; the row limit is
     * clamped to [1, max-row-limit] and the timeout to [1s, max-timeout-ms]. At
     * most {@code rowLimit} rows are read; if more exist the result is flagged as
     * truncated. Every attempt, including rejected ones, is audited.
     *
     * @param connectionName the connection profile name
     * @param query the SQL query to execute
     * @param rowLimit maximum rows to return
     * @param timeoutMs query timeout in milliseconds
     * @return the query execution result
     * @throws QueryExecutionException if validation or execution fails
     * @throws IllegalArgumentException if the connection profile does not exist
     */
    public QueryResult execute(
            final String connectionName,
            final String query,
            final int rowLimit,
            final int timeoutMs) {

        ConnectionProfile profile = findProfile(connectionName);

        ValidationResult validation = queryGuard.validate(query, profile.type());
        if (!validation.isValid()) {
            queryLogger.logValidationError(connectionName, query, validation.error());
            throw new QueryExecutionException("Query validation failed: " + validation.error());
        }

        int effectiveLimit = Math.min(Math.max(1, rowLimit), maxRowLimit);
        int effectiveTimeout = clampTimeout(timeoutMs);
        String sql = query.strip();

        long startTime = System.currentTimeMillis();

        try (ReadOnlySession session = dataSourceFactory.openSession(connectionName, effectiveTimeout);
             Statement statement = session.connection().createStatement()) {

            statement.setQueryTimeout(toSeconds(effectiveTimeout));
            // Read one extra row to detect truncation without scanning the rest.
            statement.setMaxRows(effectiveLimit + 1);
            statement.setFetchSize(Math.min(effectiveLimit + 1, MAX_FETCH_SIZE));

            try (ResultSet rs = statement.executeQuery(sql)) {
                QueryResult result = buildResult(rs, sql, effectiveLimit, startTime);
                queryLogger.logSuccess(connectionName, QueryType.SELECT, sql,
                        result.executionTimeMs(), result.rowCount(), result.truncated());
                return result;
            }

        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            queryLogger.logFailure(connectionName, QueryType.SELECT, sql, elapsed, e.getMessage());
            throw new QueryExecutionException(
                    String.format("Query failed after %dms: %s", elapsed, e.getMessage()), e);
        }
    }

    /**
     * Reads sample rows from a table.
     *
     * <p>Business rules: the table name is quoted with the engine's identifier
     * quote and the resulting query goes through the same validation, table
     * access control, session guards and auditing as {@link #execute}.
     *
     * @param connectionName the connection profile name
     * @param schemaName the schema name, may be null
     * @param tableName the table name, never null
     * @param limit maximum rows to return
     * @return the sampled rows
     * @throws QueryExecutionException if validation or execution fails
     * @throws IllegalArgumentException if the connection profile does not exist
     */
    public QueryResult sampleTable(
            final String connectionName,
            final String schemaName,
            final String tableName,
            final int limit) {
        Objects.requireNonNull(tableName, "tableName must not be null");
        DatabaseType type = findProfile(connectionName).type();
        String qualified = schemaName != null && !schemaName.isBlank()
                ? quoteIdentifier(type, schemaName) + "." + quoteIdentifier(type, tableName)
                : quoteIdentifier(type, tableName);
        return execute(connectionName, "SELECT * FROM " + qualified, limit, defaultTimeoutMs);
    }

    private ConnectionProfile findProfile(final String connectionName) {
        return profileRepository.findByName(connectionName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Connection profile not found: " + connectionName));
    }

    private int clampTimeout(final int timeoutMs) {
        return Math.min(Math.max(MIN_TIMEOUT_MS, timeoutMs), maxTimeoutMs);
    }

    private static int toSeconds(final int millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    private static String quoteIdentifier(final DatabaseType type, final String identifier) {
        return switch (type) {
            case MYSQL, MARIADB -> "`" + identifier.replace("`", "``") + "`";
            case POSTGRESQL, SQLITE -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }

    private QueryResult buildResult(
            final ResultSet rs,
            final String query,
            final int limit,
            final long startTime) throws SQLException {

        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> keys = uniqueColumnKeys(metaData);

        QueryResult.Builder builder = QueryResult.builder()
                .query(query);

        for (int i = 1; i <= columnCount; i++) {
            ColumnInfo column = ColumnInfo.create(
                    keys.get(i - 1),
                    metaData.getColumnTypeName(i),
                    metaData.getColumnType(i),
                    metaData.getColumnDisplaySize(i)
            );
            builder.addColumn(column);
        }

        int rowCount = 0;
        boolean truncated = false;

        while (rs.next()) {
            if (rowCount >= limit) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(keys.get(i - 1), formatValue(rs.getObject(i)));
            }
            builder.addRow(row);
            rowCount++;
        }

        long executionTime = System.currentTimeMillis() - startTime;

        return builder
                .truncated(truncated)
                .executionTimeMs(executionTime)
                .build();
    }

    /**
     * Returns one key per column, suffixing duplicates ({@code id}, {@code id_2})
     * so that {@code SELECT a.id, b.id} does not lose a column.
     */
    private static List<String> uniqueColumnKeys(final ResultSetMetaData metaData) throws SQLException {
        List<String> keys = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String label = metaData.getColumnLabel(i);
            String key = label;
            int suffix = 2;
            while (!used.add(key)) {
                key = label + "_" + suffix++;
            }
            keys.add(key);
        }
        return keys;
    }

    private static Object formatValue(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return String.format("[binary data: %d bytes]", bytes.length);
        }
        if (value instanceof java.sql.Clob) {
            return "[CLOB data]";
        }
        if (value instanceof java.sql.Blob) {
            return "[BLOB data]";
        }
        if (value instanceof java.sql.Array array) {
            try {
                Object[] elements = (Object[]) array.getArray();
                List<Object> formatted = new ArrayList<>(elements.length);
                for (Object element : elements) {
                    formatted.add(formatValue(element));
                }
                return formatted;
            } catch (SQLException | ClassCastException e) {
                return "[ARRAY]";
            }
        }
        if (value instanceof UUID) {
            return value.toString();
        }
        // Dates, times, JSON/JSONB, intervals, network types and any other
        // driver-specific object: use the database's own text representation.
        return value.toString();
    }

    /**
     * Returns the default row limit.
     *
     * @return the default limit
     */
    public int defaultRowLimit() {
        return defaultRowLimit;
    }

    /**
     * Returns the maximum row limit.
     *
     * @return the max limit
     */
    public int maxRowLimit() {
        return maxRowLimit;
    }

    /**
     * Returns the default query timeout.
     *
     * @return the default timeout in milliseconds
     */
    public int defaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    /**
     * Returns the effective timeout for a requested value, clamped to the configured bounds.
     *
     * @param requestedMs the requested timeout in milliseconds
     * @return the timeout that will actually be applied
     */
    public int effectiveTimeoutMs(final int requestedMs) {
        return clampTimeout(requestedMs);
    }

    /**
     * Exception thrown when query execution fails.
     */
    public static class QueryExecutionException extends RuntimeException {

        public QueryExecutionException(final String message) {
            super(message);
        }

        public QueryExecutionException(final String message, final Throwable cause) {
            super(message, cause);
        }

    }

}
