package co.fanki.sqlmcp.query.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import co.fanki.sqlmcp.connection.domain.DataSourceFactory;
import co.fanki.sqlmcp.connection.domain.DatabaseType;
import co.fanki.sqlmcp.query.domain.QueryGuard.ValidationResult;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Domain service for explaining SQL query execution plans.
 *
 * <p>Supports EXPLAIN and EXPLAIN ANALYZE for understanding
 * how the database will execute a query.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class QueryExplainer {

    private final DataSourceFactory dataSourceFactory;
    private final ConnectionProfileRepository profileRepository;
    private final QueryGuard queryGuard;

    /**
     * Creates a new QueryExplainer.
     *
     * @param dataSourceFactory the factory for data sources
     * @param profileRepository the connection profile repository
     * @param queryGuard the query validation service
     */
    public QueryExplainer(
            final DataSourceFactory dataSourceFactory,
            final ConnectionProfileRepository profileRepository,
            final QueryGuard queryGuard) {
        this.dataSourceFactory = Objects.requireNonNull(dataSourceFactory);
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.queryGuard = Objects.requireNonNull(queryGuard);
    }

    /**
     * Explains a query's execution plan without executing it.
     *
     * @param connectionName the connection profile name
     * @param query the SQL query to explain
     * @return the explanation result
     * @throws ExplainException if explanation fails
     */
    public ExplainResult explain(final String connectionName, final String query) {
        return explain(connectionName, query, false, ExplainFormat.TEXT);
    }

    /**
     * Explains a query's execution plan with optional analysis.
     *
     * @param connectionName the connection profile name
     * @param query the SQL query to explain
     * @param analyze whether to actually execute the query for real statistics
     * @param format the output format
     * @return the explanation result
     * @throws ExplainException if explanation fails
     */
    public ExplainResult explain(
            final String connectionName,
            final String query,
            final boolean analyze,
            final ExplainFormat format) {

        // Validate the query first
        ValidationResult validation = queryGuard.validate(query);
        if (!validation.isValid()) {
            throw new ExplainException("Query validation failed: " + validation.error());
        }

        String sanitizedQuery = queryGuard.sanitize(query);

        ConnectionProfile profile = profileRepository.findByName(connectionName)
                .orElseThrow(() -> new ExplainException(
                        "Connection profile not found: " + connectionName));

        // Build EXPLAIN statement based on database type
        String explainQuery = buildExplainQuery(profile.type(), sanitizedQuery, analyze, format);

        DataSource dataSource = dataSourceFactory.getDataSource(connectionName);
        long startTime = System.currentTimeMillis();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(60); // 60 second timeout for EXPLAIN ANALYZE

            List<String> planLines = new ArrayList<>();
            String planText;

            try (ResultSet rs = statement.executeQuery(explainQuery)) {
                while (rs.next()) {
                    // Different databases return plan in different formats
                    String line = rs.getString(1);
                    if (line != null) {
                        planLines.add(line);
                    }
                }
            }

            if (format == ExplainFormat.JSON && !planLines.isEmpty()) {
                // JSON format returns a single JSON string
                planText = String.join("", planLines);
            } else {
                planText = String.join("\n", planLines);
            }

            long executionTime = System.currentTimeMillis() - startTime;

            return ExplainResult.create(
                    sanitizedQuery,
                    planText,
                    planLines,
                    analyze,
                    format,
                    executionTime
            );

        } catch (SQLException e) {
            throw new ExplainException("Failed to explain query: " + e.getMessage(), e);
        }
    }

    private String buildExplainQuery(
            final DatabaseType dbType,
            final String query,
            final boolean analyze,
            final ExplainFormat format) {

        return switch (dbType) {
            case POSTGRESQL -> buildPostgresExplain(query, analyze, format);
            case MYSQL, MARIADB -> buildMySqlExplain(query, analyze, format);
            case SQLITE -> buildSqliteExplain(query, analyze);
        };
    }

    private String buildPostgresExplain(
            final String query,
            final boolean analyze,
            final ExplainFormat format) {

        StringBuilder sb = new StringBuilder("EXPLAIN (");

        // Options
        List<String> options = new ArrayList<>();
        if (analyze) {
            options.add("ANALYZE true");
            options.add("BUFFERS true");
        }

        options.add("COSTS true");
        options.add("VERBOSE false");

        switch (format) {
            case JSON -> options.add("FORMAT JSON");
            case YAML -> options.add("FORMAT YAML");
            case XML -> options.add("FORMAT XML");
            default -> options.add("FORMAT TEXT");
        }

        sb.append(String.join(", ", options));
        sb.append(") ");
        sb.append(query);

        return sb.toString();
    }

    private String buildMySqlExplain(
            final String query,
            final boolean analyze,
            final ExplainFormat format) {

        StringBuilder sb = new StringBuilder("EXPLAIN ");

        if (analyze) {
            sb.append("ANALYZE ");
        }

        if (format == ExplainFormat.JSON) {
            sb.append("FORMAT=JSON ");
        }

        sb.append(query);
        return sb.toString();
    }

    private String buildSqliteExplain(final String query, final boolean analyze) {
        if (analyze) {
            return "EXPLAIN QUERY PLAN " + query;
        }
        return "EXPLAIN " + query;
    }

    /**
     * Output format for EXPLAIN results.
     */
    public enum ExplainFormat {
        TEXT,
        JSON,
        YAML,
        XML
    }

    /**
     * Result of an EXPLAIN operation.
     */
    public static final class ExplainResult {

        private final String query;
        private final String plan;
        private final List<String> planLines;
        private final boolean analyzed;
        private final ExplainFormat format;
        private final long executionTimeMs;

        private ExplainResult(
                final String query,
                final String plan,
                final List<String> planLines,
                final boolean analyzed,
                final ExplainFormat format,
                final long executionTimeMs) {
            this.query = query;
            this.plan = plan;
            this.planLines = List.copyOf(planLines);
            this.analyzed = analyzed;
            this.format = format;
            this.executionTimeMs = executionTimeMs;
        }

        static ExplainResult create(
                final String query,
                final String plan,
                final List<String> planLines,
                final boolean analyzed,
                final ExplainFormat format,
                final long executionTimeMs) {
            return new ExplainResult(query, plan, planLines, analyzed, format, executionTimeMs);
        }

        /**
         * Returns the original query.
         *
         * @return the query
         */
        public String query() {
            return query;
        }

        /**
         * Returns the execution plan as a single string.
         *
         * @return the plan text
         */
        public String plan() {
            return plan;
        }

        /**
         * Returns the execution plan as separate lines.
         *
         * @return the plan lines
         */
        public List<String> planLines() {
            return planLines;
        }

        /**
         * Returns whether ANALYZE was used.
         *
         * @return true if analyzed
         */
        public boolean analyzed() {
            return analyzed;
        }

        /**
         * Returns the output format.
         *
         * @return the format
         */
        public ExplainFormat format() {
            return format;
        }

        /**
         * Returns the execution time in milliseconds.
         *
         * @return execution time
         */
        public long executionTimeMs() {
            return executionTimeMs;
        }

    }

    /**
     * Exception thrown when query explanation fails.
     */
    public static class ExplainException extends RuntimeException {

        public ExplainException(final String message) {
            super(message);
        }

        public ExplainException(final String message, final Throwable cause) {
            super(message, cause);
        }

    }

}
