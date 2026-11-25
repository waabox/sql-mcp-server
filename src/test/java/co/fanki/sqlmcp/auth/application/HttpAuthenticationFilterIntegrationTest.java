package co.fanki.sqlmcp.auth.application;

import co.fanki.sqlmcp.SqlMcpServerApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link HttpAuthenticationFilter}, through the full Spring context and a real HTTP server.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Testcontainers
class HttpAuthenticationFilterIntegrationTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26",
            "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";
    private static final String LIST_CONNECTIONS = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_connections","arguments":{}}}""";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private ConfigurableApplicationContext context;
    private int port;

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void start(final String... extraProperties) throws Exception {
        port = freePort();
        startOnPort(port, extraProperties);
    }

    private void startOnPort(final int serverPort, final String... extraProperties) {
        port = serverPort;
        final List<String> properties = new ArrayList<>(List.of(
                "sql-mcp.transport=http",
                "server.port=" + serverPort,
                "spring.main.web-application-type=servlet",
                "sql-mcp.connections[0].name=db",
                "sql-mcp.connections[0].type=postgresql",
                "sql-mcp.connections[0].host=" + postgres.getHost(),
                "sql-mcp.connections[0].port=" + postgres.getMappedPort(5432),
                "sql-mcp.connections[0].database=" + postgres.getDatabaseName(),
                "sql-mcp.connections[0].username=" + postgres.getUsername(),
                "sql-mcp.connections[0].password=" + postgres.getPassword()));
        properties.addAll(List.of(extraProperties));
        context = new SpringApplicationBuilder(SqlMcpServerApplication.class)
                .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    private HttpResponse<String> post(final String body, final String bearer) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void whenCallingMcp_givenNoAuthConfigured_shouldAllowAsAnonymous() throws Exception {
        start();

        final HttpResponse<String> response = post(LIST_CONNECTIONS, null);

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("db"), response.body());
    }

    @Test
    void whenCallingMcp_givenSharedTokenConfiguredAndValidToken_shouldAllow() throws Exception {
        start("sql-mcp.http.auth-token=secret-token");

        assertEquals(200, post(INITIALIZE, "secret-token").statusCode());
    }

    @Test
    void whenCallingMcp_givenSharedTokenConfiguredAndWrongToken_shouldReturn401() throws Exception {
        start("sql-mcp.http.auth-token=secret-token");

        final HttpResponse<String> response = post(INITIALIZE, "wrong");

        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    void whenCallingMcp_givenGoogleEnabledAndNoToken_shouldReturn401WithResourceMetadata() throws Exception {
        final int issuerPort = freePort();
        final String issuer = "http://localhost:" + issuerPort;
        startOnPort(issuerPort,
                "sql-mcp.auth.issuer=" + issuer,
                "sql-mcp.auth.signing-key=0123456789abcdef0123456789abcdef",
                "sql-mcp.auth.google.enabled=true",
                "sql-mcp.auth.google.client-id=client-id",
                "sql-mcp.auth.google.client-secret=client-secret",
                "sql-mcp.auth.google.allowed-domains=example.com",
                "sql-mcp.auth.google.service-account-key={}",
                "sql-mcp.auth.google.admin-email=admin@example.com");

        final HttpResponse<String> response = post(INITIALIZE, null);

        assertEquals(401, response.statusCode());
        assertEquals("Bearer resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource\"",
                response.headers().firstValue("WWW-Authenticate").orElse(null));
    }

    @Test
    void whenCallingHealth_givenAuthEnabled_shouldReturn200WithoutToken() throws Exception {
        start("sql-mcp.http.auth-token=secret-token");

        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
    }

    private HttpResponse<String> postToPath(final String rawPath) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(LIST_CONNECTIONS)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void whenCallingMcpThroughPublicPrefix_givenSharedTokenConfiguredAndNoToken_shouldNotReachTools()
            throws Exception {
        start("sql-mcp.http.auth-token=secret-token");

        for (final String path : List.of("/oauth/../mcp", "/oauth/%2e%2e/mcp", "/oauth/..;/mcp",
                "/.well-known/../mcp", "/oauth/unknown", "/.well-known/unknown")) {
            final HttpResponse<String> response = postToPath(path);

            assertTrue(response.statusCode() >= 400, path + " answered " + response.statusCode());
            assertTrue(!response.body().contains("\"db\""), path + " leaked tools: " + response.body());
        }
    }

}
