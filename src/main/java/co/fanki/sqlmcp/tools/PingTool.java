package co.fanki.sqlmcp.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

/**
 * Provides a ping tool for health checking the MCP server.
 *
 * <p>This tool responds with a pong message and the current server timestamp,
 * allowing clients to verify the server is running and responsive.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class PingTool {

    private static final String TOOL_NAME = "ping";
    private static final String TOOL_DESCRIPTION =
            "Health check tool. Returns 'pong' with current server timestamp.";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    /**
     * Creates the ping tool specification.
     *
     * <p>The ping tool takes no arguments and returns a simple pong response
     * with the current server timestamp in ISO-8601 format.
     *
     * @return the tool specification for the ping command
     */
    @Bean
    @SuppressWarnings("deprecation")
    public McpServerFeatures.SyncToolSpecification pingToolSpecification() {
        Tool tool = new Tool(TOOL_NAME, TOOL_DESCRIPTION, SCHEMA);

        return new McpServerFeatures.SyncToolSpecification(
                tool,
                (exchange, arguments) -> {
                    String response = String.format(
                            "{\"status\": \"pong\", \"timestamp\": \"%s\", \"server\": \"sql-mcp-server\"}",
                            Instant.now().toString()
                    );
                    return new CallToolResult(List.of(new TextContent(response)), false);
                }
        );
    }

}
