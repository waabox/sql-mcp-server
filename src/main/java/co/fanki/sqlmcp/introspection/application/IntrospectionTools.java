package co.fanki.sqlmcp.introspection.application;

import co.fanki.sqlmcp.introspection.domain.ColumnMetadata;
import co.fanki.sqlmcp.introspection.domain.ForeignKeyMetadata;
import co.fanki.sqlmcp.introspection.domain.SchemaIntrospector;
import co.fanki.sqlmcp.introspection.domain.SchemaIntrospector.IntrospectionException;
import co.fanki.sqlmcp.introspection.domain.TableMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * MCP tools for database schema introspection.
 *
 * <p>Provides tools to explore database structure: tables, columns,
 * foreign keys, and sample data.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class IntrospectionTools {

    private static final int DEFAULT_SAMPLE_LIMIT = 10;
    private static final int MAX_SAMPLE_LIMIT = 100;

    private final SchemaIntrospector introspector;
    private final ObjectMapper objectMapper;

    /**
     * Creates new IntrospectionTools.
     *
     * @param introspector the schema introspector service
     * @param objectMapper the JSON object mapper
     */
    public IntrospectionTools(
            final SchemaIntrospector introspector,
            final ObjectMapper objectMapper) {
        this.introspector = Objects.requireNonNull(introspector);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    // =========================================================================
    // List Tables Tool
    // =========================================================================

    private static final String LIST_TABLES_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The connection profile name"
                },
                "schema": {
                  "type": "string",
                  "description": "Schema name pattern (optional, supports % wildcard)"
                }
              },
              "required": ["connection"]
            }
            """;

    /**
     * Creates the list_tables tool specification.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification listTablesToolSpecification() {
        Tool tool = new Tool(
                "list_tables",
                "Lists all tables and views in the database. " +
                "Returns table name, schema, type (TABLE/VIEW), and description.",
                LIST_TABLES_SCHEMA
        );

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");
                    String schemaPattern = (String) arguments.get("schema");

                    if (connectionName == null || connectionName.isBlank()) {
                        return errorResult("Connection name is required");
                    }

                    try {
                        List<TableMetadata> tables = introspector.listTables(
                                connectionName, schemaPattern);

                        List<Map<String, Object>> tableList = tables.stream()
                                .map(this::tableToMap)
                                .collect(Collectors.toList());

                        Map<String, Object> result = Map.of(
                                "tables", tableList,
                                "count", tableList.size()
                        );

                        return successResult(result);
                    } catch (IntrospectionException e) {
                        return errorResult(e.getMessage());
                    }
                }
        );
    }

    // =========================================================================
    // Describe Table Tool
    // =========================================================================

    private static final String DESCRIBE_TABLE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The connection profile name"
                },
                "table": {
                  "type": "string",
                  "description": "The table name to describe"
                },
                "schema": {
                  "type": "string",
                  "description": "Schema name (optional)"
                }
              },
              "required": ["connection", "table"]
            }
            """;

    /**
     * Creates the describe_table tool specification.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification describeTableToolSpecification() {
        Tool tool = new Tool(
                "describe_table",
                "Describes a table's structure including columns, types, " +
                "constraints, and default values.",
                DESCRIBE_TABLE_SCHEMA
        );

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");
                    String tableName = (String) arguments.get("table");
                    String schemaName = (String) arguments.get("schema");

                    if (connectionName == null || connectionName.isBlank()) {
                        return errorResult("Connection name is required");
                    }
                    if (tableName == null || tableName.isBlank()) {
                        return errorResult("Table name is required");
                    }

                    try {
                        List<ColumnMetadata> columns = introspector.describeTable(
                                connectionName, schemaName, tableName);

                        if (columns.isEmpty()) {
                            return errorResult("Table not found: " + tableName);
                        }

                        List<Map<String, Object>> columnList = columns.stream()
                                .map(this::columnToMap)
                                .collect(Collectors.toList());

                        Map<String, Object> result = Map.of(
                                "table", tableName,
                                "schema", schemaName != null ? schemaName : "",
                                "columns", columnList,
                                "columnCount", columnList.size()
                        );

                        return successResult(result);
                    } catch (IntrospectionException e) {
                        return errorResult(e.getMessage());
                    }
                }
        );
    }

    // =========================================================================
    // List Foreign Keys Tool
    // =========================================================================

    private static final String LIST_FK_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The connection profile name"
                },
                "table": {
                  "type": "string",
                  "description": "The table name to get foreign keys for"
                },
                "schema": {
                  "type": "string",
                  "description": "Schema name (optional)"
                }
              },
              "required": ["connection", "table"]
            }
            """;

    /**
     * Creates the list_foreign_keys tool specification.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification listForeignKeysToolSpecification() {
        Tool tool = new Tool(
                "list_foreign_keys",
                "Lists all foreign key relationships for a table. " +
                "Shows source and target tables/columns and referential actions.",
                LIST_FK_SCHEMA
        );

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");
                    String tableName = (String) arguments.get("table");
                    String schemaName = (String) arguments.get("schema");

                    if (connectionName == null || connectionName.isBlank()) {
                        return errorResult("Connection name is required");
                    }
                    if (tableName == null || tableName.isBlank()) {
                        return errorResult("Table name is required");
                    }

                    try {
                        List<ForeignKeyMetadata> foreignKeys = introspector.listForeignKeys(
                                connectionName, schemaName, tableName);

                        List<Map<String, Object>> fkList = foreignKeys.stream()
                                .map(this::foreignKeyToMap)
                                .collect(Collectors.toList());

                        Map<String, Object> result = Map.of(
                                "table", tableName,
                                "foreignKeys", fkList,
                                "count", fkList.size()
                        );

                        return successResult(result);
                    } catch (IntrospectionException e) {
                        return errorResult(e.getMessage());
                    }
                }
        );
    }

    // =========================================================================
    // Sample Rows Tool
    // =========================================================================

    private static final String SAMPLE_ROWS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The connection profile name"
                },
                "table": {
                  "type": "string",
                  "description": "The table name to sample from"
                },
                "schema": {
                  "type": "string",
                  "description": "Schema name (optional)"
                },
                "limit": {
                  "type": "integer",
                  "description": "Maximum number of rows to return (default 10, max 100)"
                }
              },
              "required": ["connection", "table"]
            }
            """;

    /**
     * Creates the sample_rows tool specification.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification sampleRowsToolSpecification() {
        Tool tool = new Tool(
                "sample_rows",
                "Retrieves sample data rows from a table. " +
                "Useful for understanding data patterns and content.",
                SAMPLE_ROWS_SCHEMA
        );

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");
                    String tableName = (String) arguments.get("table");
                    String schemaName = (String) arguments.get("schema");
                    Integer limitArg = (Integer) arguments.get("limit");

                    if (connectionName == null || connectionName.isBlank()) {
                        return errorResult("Connection name is required");
                    }
                    if (tableName == null || tableName.isBlank()) {
                        return errorResult("Table name is required");
                    }

                    int limit = limitArg != null
                            ? Math.min(limitArg, MAX_SAMPLE_LIMIT)
                            : DEFAULT_SAMPLE_LIMIT;

                    try {
                        List<Map<String, Object>> rows = introspector.sampleRows(
                                connectionName, schemaName, tableName, limit);

                        Map<String, Object> result = Map.of(
                                "table", tableName,
                                "rows", rows,
                                "rowCount", rows.size(),
                                "limitApplied", limit
                        );

                        return successResult(result);
                    } catch (IntrospectionException e) {
                        return errorResult(e.getMessage());
                    }
                }
        );
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private Map<String, Object> tableToMap(final TableMetadata table) {
        return Map.of(
                "name", table.name(),
                "schema", table.schema() != null ? table.schema() : "",
                "type", table.type().name(),
                "qualifiedName", table.qualifiedName(),
                "comment", table.comment() != null ? table.comment() : ""
        );
    }

    private Map<String, Object> columnToMap(final ColumnMetadata column) {
        return Map.ofEntries(
                Map.entry("name", column.name()),
                Map.entry("type", column.typeDescription()),
                Map.entry("dataType", column.dataType()),
                Map.entry("nullable", column.nullable()),
                Map.entry("primaryKey", column.primaryKey()),
                Map.entry("autoIncrement", column.autoIncrement()),
                Map.entry("defaultValue", column.defaultValue() != null ? column.defaultValue() : ""),
                Map.entry("comment", column.comment() != null ? column.comment() : ""),
                Map.entry("position", column.ordinalPosition())
        );
    }

    private Map<String, Object> foreignKeyToMap(final ForeignKeyMetadata fk) {
        return Map.of(
                "name", fk.name() != null ? fk.name() : "",
                "sourceTable", fk.qualifiedSourceTable(),
                "sourceColumns", fk.sourceColumns(),
                "targetTable", fk.qualifiedTargetTable(),
                "targetColumns", fk.targetColumns(),
                "onUpdate", fk.onUpdate().name(),
                "onDelete", fk.onDelete().name()
        );
    }

    private CallToolResult successResult(final Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return new CallToolResult(List.of(new TextContent(json)), false);
        } catch (JsonProcessingException e) {
            return errorResult("JSON serialization error: " + e.getMessage());
        }
    }

    private CallToolResult errorResult(final String message) {
        String json = String.format("{\"error\": \"%s\"}", escapeJson(message));
        return new CallToolResult(List.of(new TextContent(json)), true);
    }

    private String escapeJson(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

}
