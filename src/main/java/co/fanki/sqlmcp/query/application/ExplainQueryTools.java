package co.fanki.sqlmcp.query.application;

import co.fanki.sqlmcp.query.domain.QueryExplainer;
import co.fanki.sqlmcp.query.domain.QueryExplainer.ExplainException;
import co.fanki.sqlmcp.query.domain.QueryExplainer.ExplainFormat;
import co.fanki.sqlmcp.query.domain.QueryExplainer.ExplainResult;
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

/**
 * MCP tools for explaining SQL query execution plans.
 *
 * <p>Provides tools to understand how the database will execute queries,
 * useful for query optimization and debugging.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class ExplainQueryTools {

    private final QueryExplainer queryExplainer;
    private final ObjectMapper objectMapper;

    /**
     * Creates new ExplainQueryTools.
     *
     * @param queryExplainer the query explanation service
     * @param objectMapper the JSON object mapper
     */
    public ExplainQueryTools(
            final QueryExplainer queryExplainer,
            final ObjectMapper objectMapper) {
        this.queryExplainer = Objects.requireNonNull(queryExplainer);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    // =========================================================================
    // Explain Query Tool
    // =========================================================================

    private static final String EXPLAIN_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The connection profile name"
                },
                "query": {
                  "type": "string",
                  "description": "The SQL SELECT query to explain"
                },
                "format": {
                  "type": "string",
                  "enum": ["text", "json", "yaml", "xml"],
                  "description": "Output format (default: text). JSON is useful for programmatic analysis."
                }
              },
              "required": ["connection", "query"]
            }
            """;

    /**
     * Creates the explain_query tool specification.
     *
     * <p>Shows the query execution plan without actually running the query.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification explainQueryToolSpecification() {
        Tool tool = new Tool(
                "explain_query",
                "Shows the execution plan for a SQL query without running it. " +
                "Useful for understanding query performance and optimization opportunities. " +
                "Shows estimated costs and row counts.",
                EXPLAIN_SCHEMA
        );

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");
                    String query = (String) arguments.get("query");
                    String formatStr = (String) arguments.get("format");

                    if (connectionName == null || connectionName.isBlank()) {
                        return errorResult("Connection name is required");
                    }
                    if (query == null || query.isBlank()) {
                        return errorResult("Query is required");
                    }

                    ExplainFormat format = parseFormat(formatStr);

                    try {
                        ExplainResult result = queryExplainer.explain(
                                connectionName, query, false, format);

                        return successResult(result);

                    } catch (ExplainException e) {
                        return errorResult(e.getMessage());
                    } catch (IllegalArgumentException e) {
                        return errorResult("Invalid connection: " + e.getMessage());
                    }
                }
        );
    }

    // =========================================================================
    // Analyze Query Tool
    // =========================================================================

    private static final String ANALYZE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The connection profile name"
                },
                "query": {
                  "type": "string",
                  "description": "The SQL SELECT query to analyze"
                },
                "format": {
                  "type": "string",
                  "enum": ["text", "json", "yaml", "xml"],
                  "description": "Output format (default: text)"
                }
              },
              "required": ["connection", "query"]
            }
            """;

    /**
     * Creates the analyze_query tool specification.
     *
     * <p>Actually executes the query to collect real statistics
     * about execution time, rows processed, and buffer usage.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification analyzeQueryToolSpecification() {
        Tool tool = new Tool(
                "analyze_query",
                "Executes a SQL query and shows the actual execution plan with real statistics. " +
                "WARNING: This actually runs the query! Use for SELECT queries only. " +
                "Shows actual execution times, rows processed, and buffer usage.",
                ANALYZE_SCHEMA
        );

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");
                    String query = (String) arguments.get("query");
                    String formatStr = (String) arguments.get("format");

                    if (connectionName == null || connectionName.isBlank()) {
                        return errorResult("Connection name is required");
                    }
                    if (query == null || query.isBlank()) {
                        return errorResult("Query is required");
                    }

                    ExplainFormat format = parseFormat(formatStr);

                    try {
                        ExplainResult result = queryExplainer.explain(
                                connectionName, query, true, format);

                        return successResult(result);

                    } catch (ExplainException e) {
                        return errorResult(e.getMessage());
                    } catch (IllegalArgumentException e) {
                        return errorResult("Invalid connection: " + e.getMessage());
                    }
                }
        );
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private ExplainFormat parseFormat(final String formatStr) {
        if (formatStr == null || formatStr.isBlank()) {
            return ExplainFormat.TEXT;
        }
        return switch (formatStr.toLowerCase()) {
            case "json" -> ExplainFormat.JSON;
            case "yaml" -> ExplainFormat.YAML;
            case "xml" -> ExplainFormat.XML;
            default -> ExplainFormat.TEXT;
        };
    }

    private CallToolResult successResult(final ExplainResult result) {
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("success", true);
        response.put("analyzed", result.analyzed());
        response.put("format", result.format().name().toLowerCase());
        response.put("executionTimeMs", result.executionTimeMs());
        response.put("query", result.query());

        // For JSON format, try to include as structured data
        if (result.format() == ExplainFormat.JSON) {
            try {
                Object planJson = objectMapper.readValue(result.plan(), Object.class);
                response.put("plan", planJson);
            } catch (Exception e) {
                // Fallback to string if JSON parsing fails
                response.put("plan", result.plan());
            }
        } else {
            response.put("plan", result.plan());
            response.put("planLines", result.planLines());
        }

        // Add helpful hints
        if (result.analyzed()) {
            response.put("note", "ANALYZE was used - query was actually executed to gather real statistics.");
        } else {
            response.put("note", "Estimated plan only. Use analyze_query for actual execution statistics.");
        }

        try {
            String json = objectMapper.writeValueAsString(response);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (JsonProcessingException e) {
            return errorResult("JSON serialization error: " + e.getMessage());
        }
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
