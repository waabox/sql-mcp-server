package co.fanki.sqlmcp.connection.application;

import co.fanki.sqlmcp.connection.domain.DataSourceFactory;
import co.fanki.sqlmcp.connection.domain.DataSourceFactory.TestResult;
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

/**
 * MCP tool for testing database connection profiles.
 *
 * <p>Attempts to establish a connection to the specified database
 * and returns the result including database version information.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class TestConnectionTool {

    private static final String TOOL_NAME = "test_connection";
    private static final String TOOL_DESCRIPTION =
            "Tests connectivity to a database connection profile. " +
            "Returns success status and database version if connected.";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "connection": {
                  "type": "string",
                  "description": "The name of the connection profile to test"
                }
              },
              "required": ["connection"]
            }
            """;

    private final DataSourceFactory dataSourceFactory;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new TestConnectionTool.
     *
     * @param dataSourceFactory the factory for creating data sources
     * @param objectMapper the JSON object mapper
     */
    public TestConnectionTool(
            final DataSourceFactory dataSourceFactory,
            final ObjectMapper objectMapper) {
        this.dataSourceFactory = Objects.requireNonNull(dataSourceFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /**
     * Creates the tool specification for testing connections.
     *
     * @return the tool specification
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification testConnectionToolSpecification() {
        Tool tool = new Tool(TOOL_NAME, TOOL_DESCRIPTION, SCHEMA);

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String connectionName = (String) arguments.get("connection");

                    if (connectionName == null || connectionName.isBlank()) {
                        return new CallToolResult(
                                List.of(new TextContent(
                                        "{\"success\": false, \"error\": \"Connection name is required\"}")),
                                true
                        );
                    }

                    TestResult testResult = dataSourceFactory.testConnection(connectionName);

                    Map<String, Object> result = Map.of(
                            "connection", connectionName,
                            "success", testResult.isSuccess(),
                            "message", testResult.message()
                    );

                    try {
                        String json = objectMapper.writeValueAsString(result);
                        return new CallToolResult(
                                List.of(new TextContent(json)),
                                !testResult.isSuccess()
                        );
                    } catch (JsonProcessingException e) {
                        return new CallToolResult(
                                List.of(new TextContent("Error serializing result: " + e.getMessage())),
                                true
                        );
                    }
                }
        );
    }

}
