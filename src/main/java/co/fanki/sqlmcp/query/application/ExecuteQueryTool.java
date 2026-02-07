package co.fanki.sqlmcp.query.application;

import co.fanki.sqlmcp.query.domain.QueryExecutor;
import co.fanki.sqlmcp.query.domain.QueryExecutor.QueryExecutionException;
import co.fanki.sqlmcp.query.domain.QueryResult;
import co.fanki.sqlmcp.query.domain.QueryResult.ColumnInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MCP tool for executing safe SQL queries.
 *
 * <p>Provides a secure interface for executing read-only SQL queries
 * with built-in safety controls including validation, timeouts, and row limits.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class ExecuteQueryTool {

    private static final String TOOL_NAME = "execute_query";
    private static final String TOOL_DESCRIPTION =
            "Executes a SQL SELECT query against the database. " +
            "Only read-only queries are allowed. " +
            "Results may be truncated if they exceed the row limit. " +
            "Use list_tables and describe_table first to understand the schema.";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The connection profile name"
                },
                "query": {
                  "type": "string",
                  "description": "The SQL SELECT query to execute"
                },
                "limit": {
                  "type": "integer",
                  "description": "Maximum number of rows to return (default varies by config, max 10000)"
                },
                "timeout": {
                  "type": "integer",
                  "description": "Query timeout in milliseconds (default 30000)"
                }
              },
              "required": ["connection", "query"]
            }
            """;

    private final QueryExecutor queryExecutor;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new ExecuteQueryTool.
     *
     * @param queryExecutor the query execution service
     * @param objectMapper the JSON object mapper
     */
    public ExecuteQueryTool(
            final QueryExecutor queryExecutor,
            final ObjectMapper objectMapper) {
        this.queryExecutor = Objects.requireNonNull(queryExecutor);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Creates the execute_query tool specification.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification executeQueryToolSpecification() {
        Tool tool = new Tool(TOOL_NAME, TOOL_DESCRIPTION, SCHEMA);

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");
                    String query = (String) arguments.get("query");
                    Integer limitArg = (Integer) arguments.get("limit");
                    Integer timeoutArg = (Integer) arguments.get("timeout");

                    // Validate required arguments
                    if (connectionName == null || connectionName.isBlank()) {
                        return errorResult("Connection name is required");
                    }
                    if (query == null || query.isBlank()) {
                        return errorResult("Query is required");
                    }

                    // Apply defaults
                    int limit = limitArg != null ? limitArg : queryExecutor.defaultRowLimit();
                    int timeout = timeoutArg != null ? timeoutArg : 30_000;

                    try {
                        QueryResult result = queryExecutor.execute(
                                connectionName, query, limit, timeout);

                        return successResult(result);

                    } catch (QueryExecutionException e) {
                        return errorResult(e.getMessage());
                    } catch (IllegalArgumentException e) {
                        return errorResult("Invalid connection: " + e.getMessage());
                    }
                }
        );
    }

    private CallToolResult successResult(final QueryResult result) {
        Map<String, Object> response = new LinkedHashMap<>();

        // Metadata
        response.put("success", true);
        response.put("rowCount", result.rowCount());
        response.put("totalRowCount", result.totalRowCount());
        response.put("truncated", result.truncated());
        response.put("executionTimeMs", result.executionTimeMs());

        // Columns
        List<Map<String, Object>> columns = result.columns().stream()
                .map(this::columnToMap)
                .collect(Collectors.toList());
        response.put("columns", columns);

        // Rows
        response.put("rows", result.rows());

        // Truncation warning
        if (result.truncated()) {
            response.put("warning", String.format(
                    "Results truncated. Showing %d of %d total rows. " +
                    "Use LIMIT clause or increase limit parameter to control output.",
                    result.rowCount(), result.totalRowCount()));
        }

        try {
            String json = objectMapper.writeValueAsString(response);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (JsonProcessingException e) {
            return errorResult("JSON serialization error: " + e.getMessage());
        }
    }

    private Map<String, Object> columnToMap(final ColumnInfo column) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", column.name());
        map.put("type", column.typeName());
        return map;
    }

    private CallToolResult errorResult(final String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", message);

        try {
            String json = objectMapper.writeValueAsString(response);
            return new CallToolResult(List.of(new TextContent(json)), true);
        } catch (JsonProcessingException e) {
            String fallback = String.format("{\"success\": false, \"error\": \"%s\"}",
                    escapeJson(message));
            return new CallToolResult(List.of(new TextContent(fallback)), true);
        }
    }

    private String escapeJson(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

}
