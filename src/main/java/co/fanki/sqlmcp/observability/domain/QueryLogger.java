package co.fanki.sqlmcp.observability.domain;

import co.fanki.sqlmcp.observability.domain.QueryAuditLog.ExecutionStatus;
import co.fanki.sqlmcp.observability.domain.QueryAuditLog.QueryType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Domain service for structured query logging and metrics collection.
 *
 * <p>Provides structured JSON logging for all query operations,
 * maintaining metrics for monitoring and debugging.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class QueryLogger {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("audit.query");
    private static final Logger METRICS_LOG = LoggerFactory.getLogger("metrics.query");

    private static final int MAX_QUERY_LOG_LENGTH = 500;

    private final ObjectMapper objectMapper;

    // Metrics counters
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong successfulQueries = new AtomicLong(0);
    private final AtomicLong failedQueries = new AtomicLong(0);
    private final AtomicLong totalRowsReturned = new AtomicLong(0);
    private final AtomicLong totalExecutionTimeMs = new AtomicLong(0);

    // Per-connection metrics
    private final Map<String, ConnectionMetrics> connectionMetrics = new ConcurrentHashMap<>();

    /**
     * Creates a new QueryLogger.
     *
     * @param objectMapper the JSON object mapper
     */
    public QueryLogger(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Logs a successful query execution.
     *
     * @param connectionName the connection used
     * @param queryType the type of query
     * @param query the executed query
     * @param durationMs execution duration
     * @param rowCount rows returned
     * @param truncated whether results were truncated
     */
    public void logSuccess(
            final String connectionName,
            final QueryType queryType,
            final String query,
            final long durationMs,
            final int rowCount,
            final boolean truncated) {

        QueryAuditLog auditLog = QueryAuditLog.builder()
                .connectionName(connectionName)
                .queryType(queryType)
                .query(query)
                .status(ExecutionStatus.SUCCESS)
                .durationMs(durationMs)
                .rowCount(rowCount)
                .truncated(truncated)
                .build();

        log(auditLog);
        updateMetrics(auditLog);
    }

    /**
     * Logs a failed query execution.
     *
     * @param connectionName the connection used
     * @param queryType the type of query
     * @param query the executed query
     * @param durationMs execution duration before failure
     * @param errorMessage the error message
     */
    public void logFailure(
            final String connectionName,
            final QueryType queryType,
            final String query,
            final long durationMs,
            final String errorMessage) {

        QueryAuditLog auditLog = QueryAuditLog.builder()
                .connectionName(connectionName)
                .queryType(queryType)
                .query(query)
                .status(ExecutionStatus.FAILURE)
                .durationMs(durationMs)
                .errorMessage(errorMessage)
                .build();

        log(auditLog);
        updateMetrics(auditLog);
    }

    /**
     * Logs a validation error.
     *
     * @param connectionName the connection requested
     * @param query the invalid query
     * @param validationError the validation error message
     */
    public void logValidationError(
            final String connectionName,
            final String query,
            final String validationError) {

        QueryAuditLog auditLog = QueryAuditLog.builder()
                .connectionName(connectionName)
                .queryType(QueryType.SELECT)
                .query(query)
                .status(ExecutionStatus.VALIDATION_ERROR)
                .durationMs(0)
                .errorMessage(validationError)
                .build();

        log(auditLog);
        updateMetrics(auditLog);
    }

    /**
     * Returns the current metrics snapshot.
     *
     * @return map of metric names to values
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();

        metrics.put("totalQueries", totalQueries.get());
        metrics.put("successfulQueries", successfulQueries.get());
        metrics.put("failedQueries", failedQueries.get());
        metrics.put("totalRowsReturned", totalRowsReturned.get());
        metrics.put("totalExecutionTimeMs", totalExecutionTimeMs.get());

        if (totalQueries.get() > 0) {
            metrics.put("successRate",
                    (double) successfulQueries.get() / totalQueries.get() * 100);
            metrics.put("avgExecutionTimeMs",
                    (double) totalExecutionTimeMs.get() / totalQueries.get());
        }

        metrics.put("connectionMetrics", getConnectionMetricsMap());

        return metrics;
    }

    /**
     * Logs the current metrics.
     */
    public void logMetrics() {
        try {
            String metricsJson = objectMapper.writeValueAsString(getMetrics());
            METRICS_LOG.info(metricsJson);
        } catch (JsonProcessingException e) {
            METRICS_LOG.warn("Failed to serialize metrics", e);
        }
    }

    private void log(final QueryAuditLog auditLog) {
        try {
            // Set MDC for structured logging
            MDC.put("auditId", auditLog.id());
            MDC.put("connection", auditLog.connectionName());
            MDC.put("queryType", auditLog.queryType().name());
            MDC.put("status", auditLog.status().name());

            Map<String, Object> logEntry = buildLogEntry(auditLog);
            String json = objectMapper.writeValueAsString(logEntry);

            if (auditLog.status() == ExecutionStatus.SUCCESS) {
                AUDIT_LOG.info(json);
            } else {
                AUDIT_LOG.warn(json);
            }

        } catch (JsonProcessingException e) {
            AUDIT_LOG.error("Failed to serialize audit log: {}", auditLog, e);
        } finally {
            MDC.clear();
        }
    }

    private Map<String, Object> buildLogEntry(final QueryAuditLog auditLog) {
        Map<String, Object> entry = new LinkedHashMap<>();

        entry.put("id", auditLog.id());
        entry.put("timestamp", auditLog.timestamp().toString());
        entry.put("connection", auditLog.connectionName());
        entry.put("queryType", auditLog.queryType().name());
        entry.put("status", auditLog.status().name());
        entry.put("durationMs", auditLog.durationMs());

        // Truncate long queries in logs
        entry.put("query", auditLog.truncatedQuery(MAX_QUERY_LOG_LENGTH));

        if (auditLog.status() == ExecutionStatus.SUCCESS) {
            entry.put("rowCount", auditLog.rowCount());
            entry.put("truncated", auditLog.truncated());
        } else {
            entry.put("error", auditLog.errorMessage());
        }

        if (auditLog.clientInfo() != null) {
            entry.put("clientInfo", auditLog.clientInfo());
        }

        return entry;
    }

    private void updateMetrics(final QueryAuditLog auditLog) {
        totalQueries.incrementAndGet();
        totalExecutionTimeMs.addAndGet(auditLog.durationMs());

        if (auditLog.status() == ExecutionStatus.SUCCESS) {
            successfulQueries.incrementAndGet();
            totalRowsReturned.addAndGet(auditLog.rowCount());
        } else {
            failedQueries.incrementAndGet();
        }

        // Update per-connection metrics
        connectionMetrics.computeIfAbsent(auditLog.connectionName(), k -> new ConnectionMetrics())
                .update(auditLog);
    }

    private Map<String, Object> getConnectionMetricsMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        connectionMetrics.forEach((name, metrics) -> result.put(name, metrics.toMap()));
        return result;
    }

    /**
     * Per-connection metrics tracking.
     */
    private static class ConnectionMetrics {

        private final AtomicLong queries = new AtomicLong(0);
        private final AtomicLong successes = new AtomicLong(0);
        private final AtomicLong failures = new AtomicLong(0);
        private final AtomicLong totalTimeMs = new AtomicLong(0);

        void update(final QueryAuditLog log) {
            queries.incrementAndGet();
            totalTimeMs.addAndGet(log.durationMs());
            if (log.status() == ExecutionStatus.SUCCESS) {
                successes.incrementAndGet();
            } else {
                failures.incrementAndGet();
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("queries", queries.get());
            map.put("successes", successes.get());
            map.put("failures", failures.get());
            map.put("totalTimeMs", totalTimeMs.get());
            if (queries.get() > 0) {
                map.put("avgTimeMs", (double) totalTimeMs.get() / queries.get());
            }
            return map;
        }

    }

}
