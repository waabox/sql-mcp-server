package co.fanki.sqlmcp.query.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object representing the result of a SQL query execution.
 *
 * <p>Contains column metadata, row data, and execution statistics.
 * Results may be truncated if they exceed the configured row limit.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class QueryResult {

    private final List<ColumnInfo> columns;
    private final List<Map<String, Object>> rows;
    private final int totalRowCount;
    private final boolean truncated;
    private final long executionTimeMs;
    private final String query;

    private QueryResult(
            final List<ColumnInfo> columns,
            final List<Map<String, Object>> rows,
            final int totalRowCount,
            final boolean truncated,
            final long executionTimeMs,
            final String query) {
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        this.totalRowCount = totalRowCount;
        this.truncated = truncated;
        this.executionTimeMs = executionTimeMs;
        this.query = Objects.requireNonNull(query);
    }

    /**
     * Creates a new QueryResult builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the column metadata.
     *
     * @return unmodifiable list of column info
     */
    public List<ColumnInfo> columns() {
        return columns;
    }

    /**
     * Returns the result rows.
     *
     * @return unmodifiable list of row maps
     */
    public List<Map<String, Object>> rows() {
        return rows;
    }

    /**
     * Returns the number of rows returned.
     *
     * @return the row count in this result
     */
    public int rowCount() {
        return rows.size();
    }

    /**
     * Returns the total row count before truncation.
     *
     * <p>If the result was not truncated, this equals {@link #rowCount()}.
     *
     * @return the total row count
     */
    public int totalRowCount() {
        return totalRowCount;
    }

    /**
     * Returns whether the result was truncated due to row limits.
     *
     * @return true if truncated
     */
    public boolean truncated() {
        return truncated;
    }

    /**
     * Returns the query execution time in milliseconds.
     *
     * @return execution time in ms
     */
    public long executionTimeMs() {
        return executionTimeMs;
    }

    /**
     * Returns the executed query.
     *
     * @return the SQL query string
     */
    public String query() {
        return query;
    }

    /**
     * Returns the column names.
     *
     * @return list of column names
     */
    public List<String> columnNames() {
        return columns.stream()
                .map(ColumnInfo::name)
                .toList();
    }

    @Override
    public String toString() {
        return String.format("QueryResult[columns=%d, rows=%d%s, time=%dms]",
                columns.size(), rows.size(),
                truncated ? " (truncated)" : "",
                executionTimeMs);
    }

    /**
     * Column metadata for query results.
     */
    public static final class ColumnInfo {

        private final String name;
        private final String typeName;
        private final int jdbcType;
        private final int displaySize;

        private ColumnInfo(
                final String name,
                final String typeName,
                final int jdbcType,
                final int displaySize) {
            this.name = Objects.requireNonNull(name);
            this.typeName = typeName;
            this.jdbcType = jdbcType;
            this.displaySize = displaySize;
        }

        /**
         * Creates a new ColumnInfo.
         *
         * @param name the column name
         * @param typeName the SQL type name
         * @param jdbcType the JDBC type code
         * @param displaySize the display size
         * @return a new ColumnInfo
         */
        public static ColumnInfo create(
                final String name,
                final String typeName,
                final int jdbcType,
                final int displaySize) {
            return new ColumnInfo(name, typeName, jdbcType, displaySize);
        }

        public String name() {
            return name;
        }

        public String typeName() {
            return typeName;
        }

        public int jdbcType() {
            return jdbcType;
        }

        public int displaySize() {
            return displaySize;
        }

    }

    /**
     * Builder for QueryResult.
     */
    public static final class Builder {

        private final List<ColumnInfo> columns = new ArrayList<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private int totalRowCount;
        private boolean truncated;
        private long executionTimeMs;
        private String query;

        private Builder() {
        }

        public Builder addColumn(final ColumnInfo column) {
            this.columns.add(column);
            return this;
        }

        public Builder addRow(final Map<String, Object> row) {
            this.rows.add(row);
            return this;
        }

        public Builder totalRowCount(final int totalRowCount) {
            this.totalRowCount = totalRowCount;
            return this;
        }

        public Builder truncated(final boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        public Builder executionTimeMs(final long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
            return this;
        }

        public Builder query(final String query) {
            this.query = query;
            return this;
        }

        /**
         * Builds the QueryResult.
         *
         * @return a new QueryResult instance
         */
        public QueryResult build() {
            if (totalRowCount == 0) {
                totalRowCount = rows.size();
            }
            return new QueryResult(
                    columns, rows, totalRowCount, truncated, executionTimeMs, query
            );
        }

    }

}
