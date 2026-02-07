package co.fanki.sqlmcp.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SQL MCP Server using Spring AI MCP Client.
 *
 * <p>Spawns the MCP server as a subprocess and connects via STDIO transport.
 * Creates two PostgreSQL databases within a single container:
 * <ul>
 *   <li><b>ecommerce_db</b>: E-commerce domain with products, customers, orders</li>
 *   <li><b>hr_db</b>: HR domain with employees, departments</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
class McpToolsIntegrationTest {

    private static final String ECOMMERCE_DB = "ecommerce_db";
    private static final String HR_DB = "hr_db";
    private static final String ECOMMERCE_CONNECTION = "ecommerce";
    private static final String HR_CONNECTION = "hr";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("postgres")
            .withUsername("test")
            .withPassword("test");

    private McpSyncClient mcpClient;
    private ObjectMapper objectMapper;
    private Path tempConfigFile;

    @BeforeAll
    void setup() throws Exception {
        objectMapper = new ObjectMapper();

        // Create databases and seed data
        createDatabases();
        seedEcommerceDatabase();
        seedHrDatabase();

        // Create temporary config file with connection profiles
        tempConfigFile = createTempConfigFile();

        // Start MCP client connected to our server
        mcpClient = startMcpClient();
    }

    @AfterAll
    void teardown() throws Exception {
        if (mcpClient != null) {
            mcpClient.closeGracefully();
        }
        if (tempConfigFile != null) {
            Files.deleteIfExists(tempConfigFile);
        }
    }

    private void createDatabases() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE DATABASE " + ECOMMERCE_DB);
            stmt.execute("CREATE DATABASE " + HR_DB);
        }
    }

    private void seedEcommerceDatabase() throws Exception {
        String jdbcUrl = postgres.getJdbcUrl().replace("/postgres", "/" + ECOMMERCE_DB);

        try (Connection conn = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            // Create schema
            stmt.execute("""
                CREATE TABLE categories (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    description TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE products (
                    id SERIAL PRIMARY KEY,
                    sku VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(200) NOT NULL,
                    price DECIMAL(10,2) NOT NULL,
                    stock INTEGER DEFAULT 0,
                    category_id INTEGER REFERENCES categories(id),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE customers (
                    id SERIAL PRIMARY KEY,
                    email VARCHAR(255) UNIQUE NOT NULL,
                    first_name VARCHAR(100) NOT NULL,
                    last_name VARCHAR(100) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE orders (
                    id SERIAL PRIMARY KEY,
                    order_number VARCHAR(50) UNIQUE NOT NULL,
                    customer_id INTEGER REFERENCES customers(id),
                    total DECIMAL(12,2) NOT NULL,
                    status VARCHAR(20) DEFAULT 'pending',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE order_items (
                    id SERIAL PRIMARY KEY,
                    order_id INTEGER REFERENCES orders(id),
                    product_id INTEGER REFERENCES products(id),
                    quantity INTEGER NOT NULL,
                    unit_price DECIMAL(10,2) NOT NULL
                )
                """);

            // Seed data
            stmt.execute("""
                INSERT INTO categories (name, description) VALUES
                ('Electronics', 'Electronic devices and accessories'),
                ('Books', 'Physical and digital books'),
                ('Clothing', 'Apparel and fashion items')
                """);

            stmt.execute("""
                INSERT INTO products (sku, name, price, stock, category_id) VALUES
                ('ELEC-001', 'Wireless Mouse', 29.99, 150, 1),
                ('ELEC-002', 'Mechanical Keyboard', 89.99, 75, 1),
                ('ELEC-003', 'USB-C Hub', 49.99, 200, 1),
                ('BOOK-001', 'Clean Code', 39.99, 50, 2),
                ('BOOK-002', 'Domain-Driven Design', 54.99, 30, 2),
                ('CLTH-001', 'Developer T-Shirt', 24.99, 100, 3)
                """);

            stmt.execute("""
                INSERT INTO customers (email, first_name, last_name) VALUES
                ('john@example.com', 'John', 'Doe'),
                ('jane@example.com', 'Jane', 'Smith'),
                ('bob@example.com', 'Bob', 'Wilson')
                """);

            stmt.execute("""
                INSERT INTO orders (order_number, customer_id, total, status) VALUES
                ('ORD-2024-001', 1, 119.98, 'completed'),
                ('ORD-2024-002', 2, 54.99, 'shipped'),
                ('ORD-2024-003', 1, 49.99, 'pending')
                """);

            stmt.execute("""
                INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES
                (1, 1, 2, 29.99),
                (1, 3, 1, 49.99),
                (2, 5, 1, 54.99),
                (3, 3, 1, 49.99)
                """);
        }
    }

    private void seedHrDatabase() throws Exception {
        String jdbcUrl = postgres.getJdbcUrl().replace("/postgres", "/" + HR_DB);

        try (Connection conn = DriverManager.getConnection(
                jdbcUrl, postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE departments (
                    id SERIAL PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    budget DECIMAL(15,2),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

            stmt.execute("""
                CREATE TABLE employees (
                    id SERIAL PRIMARY KEY,
                    employee_number VARCHAR(20) UNIQUE NOT NULL,
                    first_name VARCHAR(100) NOT NULL,
                    last_name VARCHAR(100) NOT NULL,
                    email VARCHAR(255) UNIQUE NOT NULL,
                    department_id INTEGER REFERENCES departments(id),
                    salary DECIMAL(12,2),
                    hire_date DATE NOT NULL,
                    manager_id INTEGER REFERENCES employees(id)
                )
                """);

            stmt.execute("CREATE INDEX idx_employees_department ON employees(department_id)");
            stmt.execute("CREATE INDEX idx_employees_manager ON employees(manager_id)");

            // Seed data
            stmt.execute("""
                INSERT INTO departments (name, budget) VALUES
                ('Engineering', 1500000.00),
                ('Product', 800000.00),
                ('Sales', 600000.00)
                """);

            stmt.execute("""
                INSERT INTO employees (employee_number, first_name, last_name, email,
                                       department_id, salary, hire_date, manager_id) VALUES
                ('EMP001', 'Alice', 'Johnson', 'alice@company.com', 1, 150000.00, '2020-01-15', NULL),
                ('EMP002', 'Carlos', 'Garcia', 'carlos@company.com', 1, 120000.00, '2021-03-20', 1),
                ('EMP003', 'Diana', 'Lee', 'diana@company.com', 1, 110000.00, '2022-06-01', 1),
                ('EMP004', 'Eve', 'Brown', 'eve@company.com', 2, 130000.00, '2019-09-10', NULL),
                ('EMP005', 'Frank', 'Miller', 'frank@company.com', 3, 95000.00, '2023-01-05', NULL)
                """);
        }
    }

    private Path createTempConfigFile() throws IOException {
        String host = postgres.getHost();
        int port = postgres.getMappedPort(5432);

        String configContent = String.format("""
            spring:
              main:
                banner-mode: off
                web-application-type: none

            sql-mcp:
              transport: stdio
              connections:
                - name: %s
                  type: postgresql
                  host: %s
                  port: %d
                  database: %s
                  username: %s
                  password: %s
                  read-only: true
                - name: %s
                  type: postgresql
                  host: %s
                  port: %d
                  database: %s
                  username: %s
                  password: %s
                  read-only: true
              query:
                default-timeout-ms: 10000
                default-row-limit: 100
                max-row-limit: 500
              tables:
                deny-list:
                  - "pg_*"
                  - "information_schema.*"

            logging:
              level:
                root: WARN
            """,
                ECOMMERCE_CONNECTION, host, port, ECOMMERCE_DB,
                postgres.getUsername(), postgres.getPassword(),
                HR_CONNECTION, host, port, HR_DB,
                postgres.getUsername(), postgres.getPassword()
        );

        Path configPath = Files.createTempFile("sql-mcp-test-", ".yml");
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            writer.write(configContent);
        }
        return configPath;
    }

    private McpSyncClient startMcpClient() {
        // Find the JAR file
        File targetDir = new File("target");
        File[] jars = targetDir.listFiles((dir, name) ->
                name.startsWith("sql-mcp-server") && name.endsWith(".jar") && !name.contains("sources"));

        if (jars == null || jars.length == 0) {
            throw new IllegalStateException(
                    "JAR file not found in target/. Run 'mvn package -DskipTests' first.");
        }

        String jarPath = jars[0].getAbsolutePath();

        ServerParameters serverParams = ServerParameters.builder("java")
                .args(
                        "-jar", jarPath,
                        "--spring.config.location=" + tempConfigFile.toAbsolutePath(),
                        "--spring.profiles.active=stdio"
                )
                .build();

        StdioClientTransport transport = new StdioClientTransport(serverParams);

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();

        client.initialize();

        return client;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private CallToolResult callTool(final String name, final Map<String, Object> args) {
        return mcpClient.callTool(new CallToolRequest(name, args));
    }

    private Map<String, Object> parseJsonResult(final CallToolResult result) throws Exception {
        assertFalse(result.content().isEmpty(), "Result should have content");
        TextContent content = (TextContent) result.content().get(0);
        return objectMapper.readValue(content.text(), new TypeReference<>() {});
    }

    // =========================================================================
    // Connection Tools Tests
    // =========================================================================

    @Nested
    @DisplayName("Connection Tools")
    class ConnectionToolsTest {

        @Test
        @DisplayName("list_connections returns both configured connections")
        void whenListingConnections_shouldReturnBothDatabases() throws Exception {
            CallToolResult result = callTool("list_connections", Map.of());

            Map<String, Object> response = parseJsonResult(result);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> connections =
                    (List<Map<String, Object>>) response.get("connections");

            assertEquals(2, connections.size());

            List<String> names = connections.stream()
                    .map(c -> (String) c.get("name"))
                    .toList();

            assertTrue(names.contains(ECOMMERCE_CONNECTION));
            assertTrue(names.contains(HR_CONNECTION));
        }

        @Test
        @DisplayName("test_connection succeeds for ecommerce database")
        void whenTestingEcommerceConnection_shouldSucceed() throws Exception {
            Map<String, Object> args = Map.of("connection", ECOMMERCE_CONNECTION);
            CallToolResult result = callTool("test_connection", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));
            // Message contains database info
            String message = (String) response.get("message");
            assertTrue(message.contains("Connected to"));
        }

        @Test
        @DisplayName("test_connection succeeds for hr database")
        void whenTestingHrConnection_shouldSucceed() throws Exception {
            Map<String, Object> args = Map.of("connection", HR_CONNECTION);
            CallToolResult result = callTool("test_connection", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));
        }

        @Test
        @DisplayName("test_connection fails for unknown connection")
        void whenTestingUnknownConnection_shouldFail() throws Exception {
            Map<String, Object> args = Map.of("connection", "unknown");
            CallToolResult result = callTool("test_connection", args);

            Map<String, Object> response = parseJsonResult(result);
            assertFalse((Boolean) response.get("success"));
        }
    }

    // =========================================================================
    // Introspection Tools Tests
    // =========================================================================

    @Nested
    @DisplayName("Introspection Tools")
    class IntrospectionToolsTest {

        @Test
        @DisplayName("list_tables returns ecommerce tables")
        void whenListingEcommerceTables_shouldReturnAllTables() throws Exception {
            Map<String, Object> args = Map.of("connection", ECOMMERCE_CONNECTION);
            CallToolResult result = callTool("list_tables", args);

            Map<String, Object> response = parseJsonResult(result);
            assertNull(response.get("error"), "Should not have error");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables =
                    (List<Map<String, Object>>) response.get("tables");

            List<String> tableNames = tables.stream()
                    .map(t -> (String) t.get("name"))
                    .toList();

            assertTrue(tableNames.contains("categories"));
            assertTrue(tableNames.contains("products"));
            assertTrue(tableNames.contains("customers"));
            assertTrue(tableNames.contains("orders"));
            assertTrue(tableNames.contains("order_items"));
        }

        @Test
        @DisplayName("list_tables returns hr tables")
        void whenListingHrTables_shouldReturnAllTables() throws Exception {
            Map<String, Object> args = Map.of("connection", HR_CONNECTION);
            CallToolResult result = callTool("list_tables", args);

            Map<String, Object> response = parseJsonResult(result);
            assertNull(response.get("error"), "Should not have error");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tables =
                    (List<Map<String, Object>>) response.get("tables");

            List<String> tableNames = tables.stream()
                    .map(t -> (String) t.get("name"))
                    .toList();

            assertTrue(tableNames.contains("departments"));
            assertTrue(tableNames.contains("employees"));
        }

        @Test
        @DisplayName("describe_table returns product columns with correct types")
        void whenDescribingProductsTable_shouldReturnCorrectSchema() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "table", "products"
            );
            CallToolResult result = callTool("describe_table", args);

            Map<String, Object> response = parseJsonResult(result);
            assertNull(response.get("error"), "Should not have error");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> columns =
                    (List<Map<String, Object>>) response.get("columns");

            assertFalse(columns.isEmpty());

            // Verify specific columns
            Map<String, Map<String, Object>> columnMap = new HashMap<>();
            for (Map<String, Object> col : columns) {
                columnMap.put((String) col.get("name"), col);
            }

            assertTrue(columnMap.containsKey("id"));
            assertTrue(columnMap.containsKey("sku"));
            assertTrue(columnMap.containsKey("name"));
            assertTrue(columnMap.containsKey("price"));
            assertTrue(columnMap.containsKey("stock"));
            assertTrue(columnMap.containsKey("category_id"));

            // Check price column type (PostgreSQL returns numeric as type)
            Map<String, Object> priceCol = columnMap.get("price");
            String type = (String) priceCol.get("type");
            assertTrue(type.toLowerCase().contains("numeric"));
        }

        @Test
        @DisplayName("list_foreign_keys returns product relationships")
        void whenListingProductForeignKeys_shouldReturnCategoryRelation() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "table", "products"
            );
            CallToolResult result = callTool("list_foreign_keys", args);

            Map<String, Object> response = parseJsonResult(result);
            assertNull(response.get("error"), "Should not have error");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> foreignKeys =
                    (List<Map<String, Object>>) response.get("foreignKeys");

            assertFalse(foreignKeys.isEmpty());

            // Should have FK to categories (check targetTable instead of referencedTable)
            boolean hasCategoryFk = foreignKeys.stream()
                    .anyMatch(fk -> {
                        String targetTable = (String) fk.get("targetTable");
                        return targetTable != null && targetTable.contains("categories");
                    });
            assertTrue(hasCategoryFk, "Should have FK to categories table");
        }

        @Test
        @DisplayName("sample_rows returns product data")
        void whenSamplingProducts_shouldReturnRows() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "table", "products",
                    "limit", 3
            );
            CallToolResult result = callTool("sample_rows", args);

            Map<String, Object> response = parseJsonResult(result);
            assertNull(response.get("error"), "Should not have error");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) response.get("rows");

            assertEquals(3, rows.size());

            // Verify row structure
            Map<String, Object> firstRow = rows.get(0);
            assertTrue(firstRow.containsKey("id"));
            assertTrue(firstRow.containsKey("sku"));
            assertTrue(firstRow.containsKey("name"));
            assertTrue(firstRow.containsKey("price"));
        }
    }

    // =========================================================================
    // Query Execution Tools Tests
    // =========================================================================

    @Nested
    @DisplayName("Query Execution Tools")
    class QueryExecutionToolsTest {

        @Test
        @DisplayName("execute_query runs SELECT on ecommerce database")
        void whenExecutingSelectOnEcommerce_shouldReturnProducts() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", "SELECT sku, name, price FROM products WHERE price > 40 ORDER BY price"
            );
            CallToolResult result = callTool("execute_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) response.get("rows");

            assertFalse(rows.isEmpty());

            // All returned products should have price > 40
            for (Map<String, Object> row : rows) {
                Number price = (Number) row.get("price");
                assertTrue(price.doubleValue() > 40);
            }
        }

        @Test
        @DisplayName("execute_query runs JOIN query across ecommerce tables")
        void whenExecutingJoinQuery_shouldReturnResults() throws Exception {
            String query = """
                SELECT p.name as product, c.name as category, p.price
                FROM products p
                JOIN categories c ON p.category_id = c.id
                WHERE c.name = 'Electronics'
                ORDER BY p.price DESC
                """;

            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", query
            );
            CallToolResult result = callTool("execute_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) response.get("rows");

            assertEquals(3, rows.size()); // 3 electronics products

            for (Map<String, Object> row : rows) {
                assertEquals("Electronics", row.get("category"));
            }
        }

        @Test
        @DisplayName("execute_query runs aggregate query on hr database")
        void whenExecutingAggregateOnHr_shouldReturnDepartmentStats() throws Exception {
            String query = """
                SELECT d.name as department, COUNT(*) as employee_count, AVG(e.salary) as avg_salary
                FROM employees e
                JOIN departments d ON e.department_id = d.id
                GROUP BY d.name
                ORDER BY employee_count DESC
                """;

            Map<String, Object> args = Map.of(
                    "connection", HR_CONNECTION,
                    "query", query
            );
            CallToolResult result = callTool("execute_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) response.get("rows");

            assertFalse(rows.isEmpty());

            // Engineering should have most employees
            Map<String, Object> firstRow = rows.get(0);
            assertEquals("Engineering", firstRow.get("department"));
        }

        @Test
        @DisplayName("execute_query blocks INSERT statement")
        void whenExecutingInsert_shouldReject() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", "INSERT INTO categories (name) VALUES ('Test')"
            );
            CallToolResult result = callTool("execute_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertFalse((Boolean) response.get("success"));

            String error = (String) response.get("error");
            assertTrue(error.toLowerCase().contains("insert") ||
                    error.toLowerCase().contains("read-only") ||
                    error.toLowerCase().contains("not allowed"));
        }

        @Test
        @DisplayName("execute_query blocks DELETE statement")
        void whenExecutingDelete_shouldReject() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", "DELETE FROM products WHERE id = 1"
            );
            CallToolResult result = callTool("execute_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertFalse((Boolean) response.get("success"));
        }

        @Test
        @DisplayName("execute_query blocks DROP statement")
        void whenExecutingDrop_shouldReject() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", "DROP TABLE products"
            );
            CallToolResult result = callTool("execute_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertFalse((Boolean) response.get("success"));
        }

        @Test
        @DisplayName("execute_query respects row limit")
        void whenQueryExceedsLimit_shouldTruncateResults() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", "SELECT * FROM products",
                    "limit", 2
            );
            CallToolResult result = callTool("execute_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows =
                    (List<Map<String, Object>>) response.get("rows");

            assertEquals(2, rows.size());
        }
    }

    // =========================================================================
    // Query Explanation Tools Tests
    // =========================================================================

    @Nested
    @DisplayName("Query Explanation Tools")
    class QueryExplanationToolsTest {

        @Test
        @DisplayName("explain_query returns execution plan")
        void whenExplainingQuery_shouldReturnPlan() throws Exception {
            String query = """
                SELECT p.name, c.name as category
                FROM products p
                JOIN categories c ON p.category_id = c.id
                WHERE p.price > 50
                """;

            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", query
            );
            CallToolResult result = callTool("explain_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));
            assertFalse((Boolean) response.get("analyzed"));

            String plan = (String) response.get("plan");
            assertNotNull(plan);
            assertFalse(plan.isEmpty());

            // PostgreSQL EXPLAIN should mention join strategy
            assertTrue(plan.toLowerCase().contains("join") ||
                    plan.toLowerCase().contains("seq scan") ||
                    plan.toLowerCase().contains("hash"));
        }

        @Test
        @DisplayName("explain_query returns JSON format when requested")
        void whenExplainingWithJsonFormat_shouldReturnJsonPlan() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", "SELECT * FROM products",
                    "format", "json"
            );
            CallToolResult result = callTool("explain_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));
            assertEquals("json", response.get("format"));

            // Plan should be structured JSON
            Object plan = response.get("plan");
            assertNotNull(plan);
        }

        @Test
        @DisplayName("analyze_query returns actual execution statistics")
        void whenAnalyzingQuery_shouldReturnActualStats() throws Exception {
            Map<String, Object> args = Map.of(
                    "connection", HR_CONNECTION,
                    "query", "SELECT * FROM employees WHERE department_id = 1"
            );
            CallToolResult result = callTool("analyze_query", args);

            Map<String, Object> response = parseJsonResult(result);
            assertTrue((Boolean) response.get("success"));
            assertTrue((Boolean) response.get("analyzed"));

            String plan = (String) response.get("plan");
            assertNotNull(plan);

            // ANALYZE output should contain actual timing
            assertTrue(plan.toLowerCase().contains("actual") ||
                    plan.toLowerCase().contains("time") ||
                    plan.toLowerCase().contains("rows"));
        }
    }

    // =========================================================================
    // Cross-Database Tests
    // =========================================================================

    @Nested
    @DisplayName("Cross-Database Operations")
    class CrossDatabaseTest {

        @Test
        @DisplayName("can switch between databases in same session")
        void whenQueryingBothDatabases_shouldReturnCorrectData() throws Exception {
            // Query ecommerce
            Map<String, Object> ecommerceArgs = Map.of(
                    "connection", ECOMMERCE_CONNECTION,
                    "query", "SELECT COUNT(*) as count FROM products"
            );
            CallToolResult ecommerceResult = callTool("execute_query", ecommerceArgs);
            Map<String, Object> ecommerceResponse = parseJsonResult(ecommerceResult);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ecommerceRows =
                    (List<Map<String, Object>>) ecommerceResponse.get("rows");
            Number productCount = (Number) ecommerceRows.get(0).get("count");

            // Query HR
            Map<String, Object> hrArgs = Map.of(
                    "connection", HR_CONNECTION,
                    "query", "SELECT COUNT(*) as count FROM employees"
            );
            CallToolResult hrResult = callTool("execute_query", hrArgs);
            Map<String, Object> hrResponse = parseJsonResult(hrResult);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hrRows =
                    (List<Map<String, Object>>) hrResponse.get("rows");
            Number employeeCount = (Number) hrRows.get(0).get("count");

            // Verify correct isolation
            assertEquals(6, productCount.intValue()); // 6 products
            assertEquals(5, employeeCount.intValue()); // 5 employees
        }

        @Test
        @DisplayName("tables from one database not visible in another")
        void whenListingTables_shouldOnlyShowCurrentDatabaseTables() throws Exception {
            // List ecommerce tables
            Map<String, Object> ecommerceArgs = Map.of("connection", ECOMMERCE_CONNECTION);
            CallToolResult ecommerceResult = callTool("list_tables", ecommerceArgs);
            Map<String, Object> ecommerceResponse = parseJsonResult(ecommerceResult);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ecommerceTables =
                    (List<Map<String, Object>>) ecommerceResponse.get("tables");

            List<String> ecommerceTableNames = ecommerceTables.stream()
                    .map(t -> (String) t.get("name"))
                    .toList();

            // HR tables should not appear in ecommerce listing
            assertFalse(ecommerceTableNames.contains("employees"));
            assertFalse(ecommerceTableNames.contains("departments"));

            // And vice versa
            Map<String, Object> hrArgs = Map.of("connection", HR_CONNECTION);
            CallToolResult hrResult = callTool("list_tables", hrArgs);
            Map<String, Object> hrResponse = parseJsonResult(hrResult);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hrTables =
                    (List<Map<String, Object>>) hrResponse.get("tables");

            List<String> hrTableNames = hrTables.stream()
                    .map(t -> (String) t.get("name"))
                    .toList();

            assertFalse(hrTableNames.contains("products"));
            assertFalse(hrTableNames.contains("orders"));
        }
    }

}
