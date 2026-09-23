package co.fanki.sqlmcp.connection.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository.ConnectionProperties;
import co.fanki.sqlmcp.observability.domain.QueryLogger;
import co.fanki.sqlmcp.query.domain.QueryExecutor;
import co.fanki.sqlmcp.query.domain.QueryGuard;
import co.fanki.sqlmcp.query.domain.QueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every supported engine rejects writes at the database level,
 * even when a profile is configured with {@code read-only: false}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Testcontainers
class ReadOnlySessionIntegrationTest {

    private static final String PROFILE = "target";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:11.4");

    @TempDir
    Path tempDir;

    private DataSourceFactory factory;

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.closeAll();
        }
    }

    @Test
    void whenWriting_givenPostgresSession_shouldBeRejectedByDatabase() throws Exception {
        seed(postgres);
        assertReadOnly(DatabaseType.POSTGRESQL, containerProfile(DatabaseType.POSTGRESQL, postgres));
    }

    @Test
    void whenWriting_givenMysqlSession_shouldBeRejectedByDatabase() throws Exception {
        seed(mysql);
        assertReadOnly(DatabaseType.MYSQL, containerProfile(DatabaseType.MYSQL, mysql));
    }

    @Test
    void whenWriting_givenMariadbSession_shouldBeRejectedByDatabase() throws Exception {
        seed(mariadb);
        assertReadOnly(DatabaseType.MARIADB, containerProfile(DatabaseType.MARIADB, mariadb));
    }

    @Test
    void whenWriting_givenSqliteSession_shouldBeRejectedByDatabase() throws Exception {
        Path file = tempDir.resolve("test.db");
        try (Connection admin = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE TABLE items (id INTEGER, name VARCHAR(50))");
            statement.execute("INSERT INTO items VALUES (1, 'one')");
        }
        ConnectionProperties properties = new ConnectionProperties();
        properties.setName(PROFILE);
        properties.setType(DatabaseType.SQLITE);
        properties.setDatabase(file.toString());
        properties.setReadOnly(false);
        assertReadOnly(DatabaseType.SQLITE, properties);
    }

    private void assertReadOnly(final DatabaseType type, final ConnectionProperties properties) throws Exception {
        ConnectionProfileRepository repository = new ConnectionProfileRepository();
        repository.setConnections(List.of(properties));
        factory = new DataSourceFactory(repository, 5000);

        assertTrue(repository.findByName(PROFILE).orElseThrow().readOnly());

        try (ReadOnlySession session = factory.openSession(PROFILE, 5000);
             Statement statement = session.connection().createStatement()) {
            SQLException error = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO items VALUES (2, 'two')"));
            assertTrue(error.getMessage().toLowerCase().replace("-", "").replace(" ", "").contains("readonly"),
                    error.getMessage());
        }

        // The rejected write must not have left anything behind.
        try (ReadOnlySession session = factory.openSession(PROFILE, 5000);
             Statement statement = session.connection().createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM items")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }

        // Sampling quotes identifiers with the engine's own quote character.
        QueryExecutor executor = new QueryExecutor(
                factory, repository, new QueryGuard(), new QueryLogger(new ObjectMapper()));
        QueryResult sample = executor.sampleTable(PROFILE, null, "items", 10);
        assertEquals(1, sample.rowCount(), type.name());
    }

    private ConnectionProperties containerProfile(
            final DatabaseType type,
            final JdbcDatabaseContainer<?> container) {
        ConnectionProperties properties = new ConnectionProperties();
        properties.setName(PROFILE);
        properties.setType(type);
        properties.setHost(container.getHost());
        properties.setPort(container.getFirstMappedPort());
        properties.setDatabase(container.getDatabaseName());
        properties.setUsername(container.getUsername());
        properties.setPassword(container.getPassword());
        properties.setReadOnly(false);
        return properties;
    }

    private void seed(final JdbcDatabaseContainer<?> container) throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS items");
            statement.execute("CREATE TABLE items (id INTEGER, name VARCHAR(50))");
            statement.execute("INSERT INTO items VALUES (1, 'one')");
        }
    }

}
