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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
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
 *   <li>STDIO - for local usage, where Claude Code starts the server as a subprocess</li>
 *   <li>Streamable HTTP (default) - for server deployment. The server is stateless: every
 *       JSON-RPC request is a self-contained POST to {@code /mcp}, so it can run
 *       behind a load balancer with several replicas and no sticky sessions.</li>
 * </ul>
 *
 * <p>Set {@code sql-mcp.transport=stdio} to enable STDIO mode.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class McpServerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerConfig.class);
    private static final String SERVER_NAME = "sql-mcp-server";
    private static final String SERVER_VERSION = "1.0.2";
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
    // STDIO Transport Configuration
    // =========================================================================

    /**
     * Wraps the process standard input so the server can detect when the
     * client closes it.
     *
     * @return the standard input wrapper
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "stdio")
    public EofAwareInputStream stdioInput() {
        return EofAwareInputStream.wrap(System.in);
    }

    /**
     * Creates the STDIO transport provider for CLI usage.
     *
     * @param objectMapper the ObjectMapper for JSON serialization
     * @param stdioInput the standard input wrapper
     * @return the configured STDIO transport provider
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "stdio")
    public StdioServerTransportProvider stdioTransportProvider(
            final ObjectMapper objectMapper,
            final EofAwareInputStream stdioInput) {
        return new StdioServerTransportProvider(objectMapper, stdioInput, System.out);
    }

    /**
     * Creates the MCP server with STDIO transport.
     *
     * @param transportProvider the STDIO transport provider
     * @param tools the list of tool specifications to register
     * @return the configured MCP server
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "stdio")
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
     * Keeps the STDIO MCP server running until the client closes standard input.
     *
     * <p>Business rule: when standard input reaches end of file the client is
     * gone, so the application shuts down (closing the database pools) instead
     * of lingering as an orphan process.
     *
     * @param server the MCP server to run
     * @param stdioInput the standard input wrapper
     * @param context the application context to close on exit
     * @return the command line runner that blocks until the client disconnects
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "stdio")
    public CommandLineRunner runStdioServer(
            final McpSyncServer server,
            final EofAwareInputStream stdioInput,
            final ConfigurableApplicationContext context) {
        return args -> {
            // The server is already started by the transport provider.
            stdioInput.awaitEof();
            LOG.info("Standard input closed by the MCP client; shutting down");
            System.exit(SpringApplication.exit(context));
        };
    }

    // =========================================================================
    // Streamable HTTP Transport Configuration (default, for server deployment)
    // =========================================================================

    /**
     * Creates the stateless Streamable HTTP transport for server deployment.
     *
     * @param objectMapper the ObjectMapper for JSON serialization
     * @return the configured Streamable HTTP transport
     */
    @Bean
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http", matchIfMissing = true)
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
