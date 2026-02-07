package co.fanki.sqlmcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;

/**
 * Configuration for the MCP server.
 *
 * <p>Supports two transport modes:
 * <ul>
 *   <li>STDIO (default) - for CLI usage and local development</li>
 *   <li>HTTP/SSE - for server deployment with web clients</li>
 * </ul>
 *
 * <p>Set {@code sql-mcp.transport=http} to enable HTTP mode.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class McpServerConfig {

    private static final String SERVER_NAME = "sql-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String SSE_MESSAGE_ENDPOINT = "/mcp/message";

    /**
     * Creates the ObjectMapper for JSON serialization.
     *
     * @return a configured ObjectMapper instance
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    // =========================================================================
    // STDIO Transport Configuration (default)
    // =========================================================================

    /**
     * Creates the STDIO transport provider for CLI usage.
     *
     * @param objectMapper the ObjectMapper for JSON serialization
     * @return the configured STDIO transport provider
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "stdio", matchIfMissing = true)
    public StdioServerTransportProvider stdioTransportProvider(final ObjectMapper objectMapper) {
        return new StdioServerTransportProvider(objectMapper);
    }

    /**
     * Creates the MCP server with STDIO transport.
     *
     * @param transportProvider the STDIO transport provider
     * @param tools the list of tool specifications to register
     * @return the configured MCP server
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "stdio", matchIfMissing = true)
    public McpSyncServer stdioMcpServer(
            final StdioServerTransportProvider transportProvider,
            final List<McpServerFeatures.SyncToolSpecification> tools) {

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(buildCapabilities())
                .build();

        tools.forEach(server::addTool);

        return server;
    }

    /**
     * Runs the STDIO MCP server and blocks until shutdown.
     *
     * @param server the MCP server to run
     * @return the command line runner that starts the server
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "stdio", matchIfMissing = true)
    public CommandLineRunner runStdioServer(final McpSyncServer server) {
        return args -> {
            // Server is already started via the transport provider
            // Block the main thread to keep the application running
            Thread.currentThread().join();
        };
    }

    // =========================================================================
    // HTTP/SSE Transport Configuration (for server deployment)
    // =========================================================================

    /**
     * Creates the HTTP/SSE transport provider for server deployment.
     *
     * @param objectMapper the ObjectMapper for JSON serialization
     * @return the configured HTTP/SSE transport provider
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http")
    public WebMvcSseServerTransportProvider httpTransportProvider(final ObjectMapper objectMapper) {
        return new WebMvcSseServerTransportProvider(objectMapper, SSE_MESSAGE_ENDPOINT);
    }

    /**
     * Creates the router function for MCP HTTP endpoints.
     *
     * @param transportProvider the HTTP/SSE transport provider
     * @return the router function for handling MCP requests
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http")
    public RouterFunction<ServerResponse> mcpRouterFunction(
            final WebMvcSseServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

    /**
     * Creates the MCP server with HTTP/SSE transport.
     *
     * @param transportProvider the HTTP/SSE transport provider
     * @param tools the list of tool specifications to register
     * @return the configured MCP server
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http")
    public McpSyncServer httpMcpServer(
            final WebMvcSseServerTransportProvider transportProvider,
            final List<McpServerFeatures.SyncToolSpecification> tools) {

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(buildCapabilities())
                .build();

        tools.forEach(server::addTool);

        return server;
    }

    // =========================================================================
    // Common Configuration
    // =========================================================================

    private ServerCapabilities buildCapabilities() {
        return ServerCapabilities.builder()
                .tools(true)
                .logging()
                .build();
    }

}
