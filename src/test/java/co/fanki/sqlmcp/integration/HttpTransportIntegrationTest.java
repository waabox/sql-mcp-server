package co.fanki.sqlmcp.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the Streamable HTTP transport.
 *
 * <p>Starts the packaged server as a subprocess in HTTP mode with a bearer token
 * and talks to it with the MCP SDK Streamable HTTP client.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class HttpTransportIntegrationTest {

    private static final String TOKEN = "test-token-123";
    private static final String CONNECTION = "db";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private Process server;
    private Path configFile;
    private Path logDir;
    private String baseUrl;

    @BeforeAll
    void setup() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
            stmt.execute("INSERT INTO items VALUES (1, 'one'), (2, 'two')");
        }

        int port = freePort();
        baseUrl = "http://localhost:" + port;
        logDir = Files.createTempDirectory("sql-mcp-http-logs-");
        configFile = Files.createTempFile("sql-mcp-http-", ".yml");
        Files.writeString(configFile, String.format("""
            sql-mcp:
              transport: http
              http:
                auth-token: %s
              connections:
                - name: %s
                  type: postgresql
                  host: %s
                  port: %d
                  database: %s
                  username: %s
                  password: %s
            server:
              port: %d
            logging:
              file:
                path: %s
              level:
                root: WARN
            """,
                TOKEN, CONNECTION, postgres.getHost(), postgres.getMappedPort(5432),
                postgres.getDatabaseName(), postgres.getUsername(), postgres.getPassword(),
                port, logDir.toAbsolutePath()));

        server = new ProcessBuilder("java", "-jar", findJar(),
                "--spring.config.location=" + configFile.toAbsolutePath(),
                "--spring.profiles.active=http")
                .redirectErrorStream(true)
                .redirectOutput(logDir.resolve("stdout.log").toFile())
                .start();

        waitForHealth();
    }

    @AfterAll
    void teardown() throws Exception {
        if (server != null) {
            server.destroy();
            server.waitFor();
        }
        Files.deleteIfExists(configFile);
    }

    @Test
    void whenCallingTools_givenValidToken_shouldExecuteQueryOverStreamableHttp() throws Exception {
        McpSyncClient client = client(TOKEN);
        try {
            client.initialize();

            ListToolsResult tools = client.listTools();
            assertTrue(tools.tools().stream().anyMatch(t -> "execute_query".equals(t.name())));

            CallToolResult result = client.callTool(new CallToolRequest("execute_query", Map.of(
                    "connection", CONNECTION,
                    "query", "SELECT id, name FROM items ORDER BY id"
            )));
            assertFalse(Boolean.TRUE.equals(result.isError()));

            Map<String, Object> response = objectMapper.readValue(
                    ((TextContent) result.content().get(0)).text(), new TypeReference<>() { });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
            assertEquals(2, rows.size());
            assertEquals("one", rows.get(0).get("name"));
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void whenInitializing_givenNewerClientCapabilities_shouldIgnoreUnknownFields() throws Exception {
        // Claude Code >= 2.1.281 advertises elicitation modes that MCP SDK 0.12.1 does not model.
        String body = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
              "protocolVersion":"2025-06-18",
              "capabilities":{"elicitation":{"form":{},"url":{}}},
              "clientInfo":{"name":"test","version":"0"}}}
            """;
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        Map<String, Object> message = objectMapper.readValue(response.body(), new TypeReference<>() { });
        assertFalse(message.containsKey("error"), "initialize failed: " + response.body());
        assertTrue(message.containsKey("result"));
    }

    @Test
    void whenInitializing_givenWrongToken_shouldFail() {
        McpSyncClient client = client("wrong-token");
        try {
            assertThrows(RuntimeException.class, client::initialize);
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    void whenPostingToMcpEndpoint_givenNoToken_shouldReturnUnauthorized() throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void whenCallingHealth_givenNoToken_shouldReturnOk() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    @Test
    void whenCallingLegacySseEndpoint_givenValidToken_shouldNotExist() throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/sse"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    private McpSyncClient client(final String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .customizeRequest(request -> request.header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
    }

    private void waitForHealth() throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!server.isAlive()) {
                throw new IllegalStateException("Server exited: " + Files.readString(logDir.resolve("stdout.log")));
            }
            try {
                HttpResponse<String> response = httpClient.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException notYetUp) {
                // retry
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Server did not become healthy in time");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String findJar() {
        File[] jars = new File("target").listFiles((dir, name) ->
                name.startsWith("sql-mcp-server") && name.endsWith(".jar") && !name.contains("sources"));
        if (jars == null || jars.length == 0) {
            throw new IllegalStateException("JAR file not found in target/. Run 'mvn package -DskipTests' first.");
        }
        return jars[0].getAbsolutePath();
    }

}
