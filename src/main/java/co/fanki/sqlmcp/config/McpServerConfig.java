package co.fanki.sqlmcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
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
 *   <li>Streamable HTTP - for server deployment. The server is stateless: every
 *       JSON-RPC request is a self-contained POST to {@code /mcp}, so it can run
 *       behind a load balancer with several replicas and no sticky sessions.</li>
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
    private static final String MCP_ENDPOINT = "/mcp";

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
    // Streamable HTTP Transport Configuration (for server deployment)
    // =========================================================================

    /**
     * Creates the stateless Streamable HTTP transport for server deployment.
     *
     * @param objectMapper the ObjectMapper for JSON serialization
     * @return the configured Streamable HTTP transport
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http")
    public WebMvcStatelessServerTransport httpTransport(final ObjectMapper objectMapper) {
        return WebMvcStatelessServerTransport.builder()
                .objectMapper(objectMapper)
                .messageEndpoint(MCP_ENDPOINT)
                .build();
    }

    /**
     * Creates the router function for the MCP HTTP endpoint.
     *
     * @param transport the Streamable HTTP transport
     * @return the router function for handling MCP requests
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http")
    public RouterFunction<ServerResponse> mcpRouterFunction(final WebMvcStatelessServerTransport transport) {
        return transport.getRouterFunction();
    }

    /**
     * Creates the MCP server with the stateless Streamable HTTP transport.
     *
     * <p>The tool specifications are shared with the STDIO server; none of them
     * uses the session exchange, so they are adapted to stateless handlers.
     *
     * @param transport the Streamable HTTP transport
     * @param tools the list of tool specifications to register
     * @return the configured MCP server
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http")
    public McpStatelessSyncServer httpMcpServer(
            final WebMvcStatelessServerTransport transport,
            final List<McpServerFeatures.SyncToolSpecification> tools) {

        List<McpStatelessServerFeatures.SyncToolSpecification> statelessTools = tools.stream()
                .map(McpServerConfig::toStateless)
                .toList();

        return McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(statelessTools)
                .build();
    }

    private static McpStatelessServerFeatures.SyncToolSpecification toStateless(
            final McpServerFeatures.SyncToolSpecification spec) {
        return new McpStatelessServerFeatures.SyncToolSpecification(
                spec.tool(),
                (context, request) -> spec.callHandler().apply(null, request));
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
