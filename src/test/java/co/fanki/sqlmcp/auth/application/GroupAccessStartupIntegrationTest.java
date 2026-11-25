package co.fanki.sqlmcp.auth.application;

import co.fanki.sqlmcp.SqlMcpServerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that the Spring context refuses to start when a group is mapped but the Google service account
 * is missing.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Testcontainers
class GroupAccessStartupIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void whenStarting_givenGroupEntryWithoutServiceAccountKey_shouldFailNamingTheKey() throws Exception {
        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        final List<String> properties = List.of(
                "sql-mcp.transport=http",
                "server.port=" + port,
                "spring.main.web-application-type=servlet",
                "sql-mcp.connections[0].name=analytics",
                "sql-mcp.connections[0].type=postgresql",
                "sql-mcp.connections[0].host=" + postgres.getHost(),
                "sql-mcp.connections[0].port=" + postgres.getMappedPort(5432),
                "sql-mcp.connections[0].database=" + postgres.getDatabaseName(),
                "sql-mcp.connections[0].username=" + postgres.getUsername(),
                "sql-mcp.connections[0].password=" + postgres.getPassword(),
                "sql-mcp.http.auth-token=shared-secret",
                "sql-mcp.access[0].group=data@example.com",
                "sql-mcp.access[0].connections[0]=analytics",
                "sql-mcp.auth.issuer=http://localhost:" + port,
                "sql-mcp.auth.signing-key=0123456789abcdef0123456789abcdef",
                "sql-mcp.auth.google.enabled=true",
                "sql-mcp.auth.google.client-id=google-client-id",
                "sql-mcp.auth.google.client-secret=google-client-secret",
                "sql-mcp.auth.google.allowed-domains=example.com");

        final Exception failure = assertThrows(Exception.class, () -> {
            final ConfigurableApplicationContext context = new SpringApplicationBuilder(SqlMcpServerApplication.class)
                    .run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
            context.close();
        });

        Throwable cause = failure;
        boolean named = false;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("service-account-key")) {
                named = true;
            }
            cause = cause.getCause();
        }
        assertTrue(named, "startup failure should name service-account-key: " + failure);
    }
}
