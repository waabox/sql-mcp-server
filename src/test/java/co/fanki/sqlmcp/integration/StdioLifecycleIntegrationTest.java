package co.fanki.sqlmcp.integration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Process lifecycle tests for the STDIO transport.
 *
 * <p>Uses a configuration without {@code web-application-type: none}, like a
 * real user setup, so the embedded HTTP server is started.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class StdioLifecycleIntegrationTest {

    private final List<McpSyncClient> clients = new ArrayList<>();
    private Path configFile;
    private Path logDir;

    @BeforeEach
    void setUp() throws IOException {
        logDir = Files.createTempDirectory("sql-mcp-stdio-logs-");
        configFile = Files.createTempFile("sql-mcp-stdio-", ".yml");
        Files.writeString(configFile, String.format("""
            sql-mcp:
              transport: stdio
              connections: []
            logging:
              file:
                path: %s
            """, logDir.toAbsolutePath()));
    }

    @AfterEach
    void tearDown() throws IOException {
        clients.forEach(McpSyncClient::closeGracefully);
        Files.deleteIfExists(configFile);
    }

    @Test
    void whenStartingTwoStdioServers_givenPort8080InUse_shouldBothInitialize() throws Exception {
        ServerSocket blocker = tryBind(8080);
        try {
            McpSyncClient first = startClient();
            McpSyncClient second = startClient();

            first.initialize();
            second.initialize();

            assertFalse(first.listTools().tools().isEmpty());
            assertFalse(second.listTools().tools().isEmpty());
        } finally {
            if (blocker != null) {
                blocker.close();
            }
        }
    }

    @Test
    void whenClientClosesStdin_givenRunningStdioServer_shouldExit() throws Exception {
        Process process = new ProcessBuilder("java", "-jar", findJar(),
                "--spring.config.additional-location=" + configFile.toAbsolutePath())
                .redirectErrorStream(true)
                .redirectOutput(logDir.resolve("stdout.log").toFile())
                .start();
        try {
            // Give the server time to start, then disconnect like a client would.
            Thread.sleep(8000);
            assertTrue(process.isAlive(), "server should be running before stdin is closed");

            process.getOutputStream().close();

            boolean exited = process.waitFor(20, TimeUnit.SECONDS);
            assertTrue(exited, "server should exit after stdin is closed");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    private McpSyncClient startClient() {
        ServerParameters params = ServerParameters.builder("java")
                .args("-jar", findJar(),
                        "--spring.config.additional-location=" + configFile.toAbsolutePath())
                .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(params))
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        clients.add(client);
        return client;
    }

    private static ServerSocket tryBind(final int port) {
        try {
            return new ServerSocket(port);
        } catch (IOException alreadyInUse) {
            // Something else already holds the port, which is the scenario under test.
            return null;
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
