# sql-mcp-server

A lightweight MCP server that gives LLMs read-only access to SQL databases.
Built for analysts, developers, and SREs who want Claude to explore schemas,
run SELECT queries, and explain execution plans—without risking data mutations.

**Java 21** | **Spring Boot 3.4** | **[MCP Java SDK](https://modelcontextprotocol.io/sdk/java/mcp-overview)**

## Quickstart

### 1. Build

```bash
git clone https://github.com/waabox/sql-mcp-server.git
cd sql-mcp-server
./mvnw clean package -DskipTests
```

### 2. Configure

Create `application.yml`:

```yaml
sql-mcp:
  transport: stdio
  connections:
    - name: local
      type: postgresql
      host: localhost
      port: 5432
      database: mydb
      username: myuser
      password: mypass
      read-only: true
```

### 3. Add to Claude Code

**Local (STDIO):**
```bash
claude mcp add sql -- java -jar /path/to/sql-mcp-server-1.0.0-SNAPSHOT.jar \
  --spring.config.location=/path/to/application.yml
```

Or create `.mcp.json` in your project root:
```json
{
  "mcpServers": {
    "sql": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/sql-mcp-server-1.0.0-SNAPSHOT.jar",
        "--spring.config.location=/path/to/application.yml"
      ]
    }
  }
}
```

**Remote (HTTP/SSE)** - when deployed on a server:
```bash
claude mcp add sql --transport sse http://sql-mcp.example.com/sse
```

Or in `.mcp.json`:
```json
{
  "mcpServers": {
    "sql": {
      "url": "http://sql-mcp.example.com/sse",
      "transport": "sse"
    }
  }
}
```

### 4. Try it

Ask Claude: *"List all tables in the local database"*

**Tool call (`list_tables`):**
```json
{ "connection": "local" }
```

**Response:**
```json
{
  "tables": [
    { "name": "users", "type": "TABLE", "schema": "public" },
    { "name": "orders", "type": "TABLE", "schema": "public" },
    { "name": "products", "type": "TABLE", "schema": "public" }
  ]
}
```

---

Ask Claude: *"Show me the top 5 users by order count"*

**Tool call (`execute_query`):**
```json
{
  "connection": "local",
  "query": "SELECT u.id, u.name, COUNT(o.id) as order_count FROM users u LEFT JOIN orders o ON o.user_id = u.id GROUP BY u.id, u.name ORDER BY order_count DESC LIMIT 5",
  "limit": 5
}
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `connection` | string | Yes | Connection profile name from `application.yml` |
| `query` | string | Yes | SQL SELECT query to execute |
| `limit` | integer | No | Max rows to return (default: 1000) |

**Response:**
```json
{
  "columns": [
    { "name": "id", "type": "int8" },
    { "name": "name", "type": "varchar" },
    { "name": "order_count", "type": "int8" }
  ],
  "rows": [
    { "id": 42, "name": "Alice", "order_count": 127 },
    { "id": 17, "name": "Bob", "order_count": 98 },
    { "id": 8, "name": "Carol", "order_count": 84 }
  ],
  "rowCount": 3,
  "executionTimeMs": 45
}
```

| Field | Description |
|-------|-------------|
| `columns` | Column metadata (name and SQL type) |
| `rows` | Result rows as JSON objects |
| `rowCount` | Number of rows returned |
| `executionTimeMs` | Query execution time in milliseconds |

## Supported Databases

| Database       | Status    |
|----------------|-----------|
| PostgreSQL     | Supported |
| MySQL/MariaDB  | Supported |
| SQLite         | Supported |

## Safety

The server enforces read-only access at multiple levels:

- **Statement validation**: Only `SELECT`, `WITH`, and `EXPLAIN` statements are allowed. Any query starting with `INSERT`, `UPDATE`, `DELETE`, `DROP`, `CREATE`, `ALTER`, `TRUNCATE`, `GRANT`, or `REVOKE` is rejected before execution.

- **Dangerous pattern detection**: Queries containing embedded DDL/DML keywords (e.g., `SELECT * FROM users; DROP TABLE users`) are blocked. Common SQL injection patterns (`' OR '1'='1`, `UNION ALL SELECT`, stacked queries) are detected and rejected.

- **Table access control**: Configure allow/deny lists to restrict which tables can be queried.

**Important:** This is a defense-in-depth layer, not a replacement for database-level permissions. Always use a read-only database user with minimal privileges.

## MCP Tools

| Tool | Description |
|------|-------------|
| `list_connections` | List configured database profiles |
| `test_connection` | Verify database connectivity |
| `list_tables` | List all tables and views |
| `describe_table` | Get column details, types, constraints |
| `list_foreign_keys` | Discover table relationships |
| `sample_rows` | Preview table data |
| `execute_query` | Run SELECT queries with safety guards |
| `explain_query` | Show execution plan (no execution) |
| `analyze_query` | Run EXPLAIN ANALYZE with actual stats |

## Configuration

### Full Example

```yaml
sql-mcp:
  transport: stdio  # or 'http' for server deployment

  connections:
    - name: production
      type: postgresql
      host: db.example.com
      port: 5432
      database: appdb
      username: ${DB_USER}
      password: ${DB_PASS}
      read-only: true

  query:
    default-timeout-ms: 30000
    default-row-limit: 1000
    max-row-limit: 10000

  tables:
    deny-list:
      - "pg_*"
      - "information_schema.*"
      - "*_audit"
      - "credentials"
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SQL_MCP_TRANSPORT` | `stdio` or `http` | `stdio` |
| `DB_USER` | Database username | - |
| `DB_PASS` | Database password | - |

## Deployment

### Docker

```bash
docker build -t sql-mcp-server:latest .

docker run -d \
  -p 8080:8080 \
  -e SQL_MCP_TRANSPORT=http \
  -e DB_USER=myuser \
  -e DB_PASS=mypass \
  -v ./application.yml:/app/config/application.yml \
  sql-mcp-server:latest \
  --spring.config.location=/app/config/application.yml
```

### Kubernetes

See [docs/kubernetes.md](docs/kubernetes.md) for full Kubernetes manifests and Helm values.

## Documentation

- [Kubernetes Deployment](docs/kubernetes.md) - ConfigMap, Secrets, Deployment, Helm
- [Usage Scenarios](docs/usage-scenarios.md) - Real-world examples with Claude

## Testing

```bash
./mvnw test
```

### Integration Test Architecture

The integration tests spawn the MCP server as a subprocess and communicate via STDIO using the **Spring AI MCP Client** (`spring-ai-mcp`).

**Setup:**
1. Testcontainers starts a PostgreSQL 16 container
2. Two databases are created: `ecommerce_db` and `hr_db`
3. Each database is seeded with realistic schema and data
4. A temporary `application.yml` is generated with dynamic port mappings
5. The MCP server JAR is launched as a subprocess
6. `McpSyncClient` connects via `StdioClientTransport`

```
┌─────────────────┐      STDIO       ┌─────────────────┐
│   Test (JUnit)  │◄─────────────── ►│  MCP Server     │
│   McpSyncClient │                  │  (subprocess)   │
└─────────────────┘                  └────────┬────────┘
                                              │
                                              ▼
                                     ┌─────────────────┐
                                     │  PostgreSQL     │
                                     │  (Testcontainer)│
                                     ├─────────────────┤
                                     │ ecommerce_db    │
                                     │ - products      │
                                     │ - orders        │
                                     │ - customers     │
                                     ├─────────────────┤
                                     │ hr_db           │
                                     │ - employees     │
                                     │ - departments   │
                                     └─────────────────┘
```

**Test databases:**

| Database | Tables | Purpose |
|----------|--------|---------|
| `ecommerce_db` | categories, products, customers, orders, order_items | E-commerce domain with FKs |
| `hr_db` | departments, employees | HR domain with self-referencing FK |

**Test coverage (21 tests):**
- Connection management (list, test, unknown connection)
- Schema introspection (list tables, describe, foreign keys, sample rows)
- Query execution (SELECT, JOIN, aggregates, row limits)
- Safety guards (blocks INSERT, DELETE, DROP)
- Query explanation (EXPLAIN, ANALYZE, JSON format)
- Cross-database isolation

**Key test example:**

```java
@Test
void whenExecutingJoinQuery_shouldReturnResults() throws Exception {
    String query = """
        SELECT p.name as product, c.name as category
        FROM products p
        JOIN categories c ON p.category_id = c.id
        WHERE c.name = 'Electronics'
        """;

    Map<String, Object> args = Map.of(
        "connection", "ecommerce",
        "query", query
    );

    CallToolResult result = mcpClient.callTool(
        new CallToolRequest("execute_query", args)
    );

    // Parse and assert response...
}
```

## Roadmap

- [x] PostgreSQL, MySQL, SQLite support
- [x] Schema introspection tools
- [x] Safe query executor
- [x] Query explanation mode
- [x] Health check endpoint (`/health`)
- [ ] Prometheus metrics
- [ ] Query cost guards (pre-flight EXPLAIN)
- [ ] Result pagination

## License

MIT - Emiliano Arango
