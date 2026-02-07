package co.fanki.sqlmcp.query.domain;

import co.fanki.sqlmcp.connection.domain.DataSourceFactory;
import co.fanki.sqlmcp.query.domain.QueryGuard.ValidationResult;
import co.fanki.sqlmcp.query.domain.QueryResult.ColumnInfo;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Domain service for executing SQL queries with safety controls.
 *
 * <p>Provides safe query execution with:
 * <ul>
 *   <li>Query validation via {@link QueryGuard}</li>
 *   <li>Configurable timeouts</li>
 *   <li>Row limits with truncation</li>
 *   <li>Normalized result format</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp.query")
public class QueryExecutor {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_ROW_LIMIT = 1000;
    private static final int MAX_ROW_LIMIT = 10_000;

    private final DataSourceFactory dataSourceFactory;
    private final QueryGuard queryGuard;

    private int defaultTimeoutMs = DEFAULT_TIMEOUT_MS;
    private int defaultRowLimit = DEFAULT_ROW_LIMIT;
    private int maxRowLimit = MAX_ROW_LIMIT;

    /**
     * Creates a new QueryExecutor.
     *
     * @param dataSourceFactory the factory for data sources
     * @param queryGuard the query validation service
     */
    public QueryExecutor(
            final DataSourceFactory dataSourceFactory,
            final QueryGuard queryGuard) {
        this.dataSourceFactory = Objects.requireNonNull(dataSourceFactory);
        this.queryGuard = Objects.requireNonNull(queryGuard);
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
     * @throws QueryExecutionException if execution fails
     */
    public QueryResult execute(final String connectionName, final String query) {
        return execute(connectionName, query, defaultRowLimit, defaultTimeoutMs);
    }

    /**
     * Executes a SQL query with custom settings.
     *
     * @param connectionName the connection profile name
     * @param query the SQL query to execute
     * @param rowLimit maximum rows to return
     * @param timeoutMs query timeout in milliseconds
     * @return the query execution result
     * @throws QueryExecutionException if execution fails
     */
    public QueryResult execute(
            final String connectionName,
            final String query,
            final int rowLimit,
            final int timeoutMs) {

        // Validate the query
        ValidationResult validation = queryGuard.validate(query);
        if (!validation.isValid()) {
            throw new QueryExecutionException("Query validation failed: " + validation.error());
        }

        // Sanitize the query
        String sanitizedQuery = queryGuard.sanitize(query);

        // Apply limits
        int effectiveLimit = Math.min(Math.max(1, rowLimit), maxRowLimit);
        int effectiveTimeout = Math.max(1000, timeoutMs);

        DataSource dataSource = dataSourceFactory.getDataSource(connectionName);

        long startTime = System.currentTimeMillis();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            // Set query timeout (in seconds)
            statement.setQueryTimeout(effectiveTimeout / 1000);

            // Set fetch size for efficiency
            statement.setFetchSize(Math.min(effectiveLimit + 1, 1000));

            try (ResultSet rs = statement.executeQuery(sanitizedQuery)) {
                return buildResult(rs, sanitizedQuery, effectiveLimit, startTime);
            }

        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            throw new QueryExecutionException(
                    String.format("Query failed after %dms: %s", elapsed, e.getMessage()), e);
        }
    }

    private QueryResult buildResult(
            final ResultSet rs,
            final String query,
            final int limit,
            final long startTime) throws SQLException {

        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        QueryResult.Builder builder = QueryResult.builder()
                .query(query);

        // Build column info
        for (int i = 1; i <= columnCount; i++) {
            ColumnInfo column = ColumnInfo.create(
                    metaData.getColumnLabel(i),
                    metaData.getColumnTypeName(i),
                    metaData.getColumnType(i),
                    metaData.getColumnDisplaySize(i)
            );
            builder.addColumn(column);
        }

        // Build rows
        int rowCount = 0;
        boolean truncated = false;

        while (rs.next()) {
            if (rowCount >= limit) {
                truncated = true;
                // Count remaining rows for total
                while (rs.next()) {
                    rowCount++;
                }
                break;
            }

            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnLabel(i);
                Object value = formatValue(rs.getObject(i));
                row.put(columnName, value);
            }
            builder.addRow(row);
            rowCount++;
        }

        long executionTime = System.currentTimeMillis() - startTime;

        return builder
                .totalRowCount(rowCount)
                .truncated(truncated)
                .executionTimeMs(executionTime)
                .build();
    }

    private Object formatValue(final Object value) {
        if (value == null) {
            return null;
        }
        // Convert complex types to strings for JSON serialization
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            if (bytes.length > 100) {
                return String.format("[binary data: %d bytes]", bytes.length);
            }
            return "[binary data]";
        }
        if (value instanceof java.sql.Clob) {
            return "[CLOB data]";
        }
        if (value instanceof java.sql.Blob) {
            return "[BLOB data]";
        }
        if (value instanceof java.sql.Array) {
            try {
                return ((java.sql.Array) value).getArray();
            } catch (SQLException e) {
                return "[ARRAY]";
            }
        }
        // Convert dates to ISO strings
        if (value instanceof java.sql.Timestamp) {
            return value.toString();
        }
        if (value instanceof java.sql.Date) {
            return value.toString();
        }
        if (value instanceof java.sql.Time) {
            return value.toString();
        }
        return value;
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
