package co.fanki.sqlmcp.observability.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable value object representing an audit log entry for a query execution.
 *
 * <p>Captures all relevant information about a query execution for
 * auditing, debugging, and performance analysis.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class QueryAuditLog {

    private final String id;
    private final Instant timestamp;
    private final String connectionName;
    private final QueryType queryType;
    private final String query;
    private final ExecutionStatus status;
    private final long durationMs;
    private final int rowCount;
    private final boolean truncated;
    private final String errorMessage;
    private final String clientInfo;

    private QueryAuditLog(
            final String id,
            final Instant timestamp,
            final String connectionName,
            final QueryType queryType,
            final String query,
            final ExecutionStatus status,
            final long durationMs,
            final int rowCount,
            final boolean truncated,
            final String errorMessage,
            final String clientInfo) {
        this.id = Objects.requireNonNull(id);
        this.timestamp = Objects.requireNonNull(timestamp);
        this.connectionName = Objects.requireNonNull(connectionName);
        this.queryType = Objects.requireNonNull(queryType);
        this.query = Objects.requireNonNull(query);
        this.status = Objects.requireNonNull(status);
        this.durationMs = durationMs;
        this.rowCount = rowCount;
        this.truncated = truncated;
        this.errorMessage = errorMessage;
        this.clientInfo = clientInfo;
    }

    /**
     * Creates a new QueryAuditLog builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the unique audit log ID.
     *
     * @return the log entry ID
     */
    public String id() {
        return id;
    }

    /**
     * Returns when the query was executed.
     *
     * @return the execution timestamp
     */
    public Instant timestamp() {
        return timestamp;
    }

    /**
     * Returns the connection profile name used.
     *
     * @return the connection name
     */
    public String connectionName() {
        return connectionName;
    }

    /**
     * Returns the type of query operation.
     *
     * @return the query type
     */
    public QueryType queryType() {
        return queryType;
    }

    /**
     * Returns the executed query.
     *
     * @return the SQL query
     */
    public String query() {
        return query;
    }

    /**
     * Returns the execution status.
     *
     * @return the status
     */
    public ExecutionStatus status() {
        return status;
    }

    /**
     * Returns the execution duration in milliseconds.
     *
     * @return duration in ms
     */
    public long durationMs() {
        return durationMs;
    }

    /**
     * Returns the number of rows returned.
     *
     * @return the row count
     */
    public int rowCount() {
        return rowCount;
    }

    /**
     * Returns whether results were truncated.
     *
     * @return true if truncated
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Returns the error message if execution failed.
     *
     * @return the error message, or null if successful
     */
    public String errorMessage() {
        return errorMessage;
    }

    /**
     * Returns client information if available.
     *
     * @return client info, may be null
     */
    public String clientInfo() {
        return clientInfo;
    }

    /**
     * Returns a truncated version of the query for logging.
     *
     * @param maxLength maximum length
     * @return the truncated query
     */
    public String truncatedQuery(final int maxLength) {
        if (query.length() <= maxLength) {
            return query;
        }
        return query.substring(0, maxLength - 3) + "...";
    }

    @Override
    public String toString() {
        return String.format("QueryAuditLog[id=%s, type=%s, status=%s, duration=%dms, rows=%d]",
                id, queryType, status, durationMs, rowCount);
    }

    /**
     * Types of query operations.
     */
    public enum QueryType {
        SELECT,
        EXPLAIN,
        ANALYZE,
        INTROSPECTION,
        CONNECTION_TEST
    }

    /**
     * Execution status.
     */
    public enum ExecutionStatus {
        SUCCESS,
        FAILURE,
        TIMEOUT,
        VALIDATION_ERROR
    }

    /**
     * Builder for QueryAuditLog.
     */
    public static final class Builder {

        private String id;
        private Instant timestamp;
        private String connectionName;
        private QueryType queryType;
        private String query;
        private ExecutionStatus status;
        private long durationMs;
        private int rowCount;
        private boolean truncated;
        private String errorMessage;
        private String clientInfo;

        private Builder() {
            this.id = UUID.randomUUID().toString();
            this.timestamp = Instant.now();
        }

        public Builder id(final String id) {
            this.id = id;
            return this;
        }

        public Builder timestamp(final Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder connectionName(final String connectionName) {
            this.connectionName = connectionName;
            return this;
        }

        public Builder queryType(final QueryType queryType) {
            this.queryType = queryType;
            return this;
        }

        public Builder query(final String query) {
            this.query = query;
            return this;
        }

        public Builder status(final ExecutionStatus status) {
            this.status = status;
            return this;
        }

        public Builder durationMs(final long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder rowCount(final int rowCount) {
            this.rowCount = rowCount;
            return this;
        }

        public Builder truncated(final boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder errorMessage(final String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder clientInfo(final String clientInfo) {
            this.clientInfo = clientInfo;
            return this;
        }

        /**
         * Builds the QueryAuditLog instance.
         *
         * @return a new QueryAuditLog
         */
        public QueryAuditLog build() {
            return new QueryAuditLog(
                    id, timestamp, connectionName, queryType, query,
                    status, durationMs, rowCount, truncated, errorMessage, clientInfo
            );
        }

    }

}
