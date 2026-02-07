# sql-mcp-server

A minimal, fast MCP server that exposes SQL databases to LLMs through a clean, typed and controllable protocol.

Built with **Java 21** and **Spring Boot 3.4**, using the official [MCP Java SDK](https://modelcontextprotocol.io/sdk/java/mcp-overview).

## Features

### Schema Introspection
- `list_tables` - List all tables and views with metadata
- `describe_table` - Get column details, types, constraints, defaults
- `list_foreign_keys` - Discover table relationships
- `sample_rows` - Preview table data (configurable limit)

### Safe Query Execution
- `execute_query` - Execute SELECT queries with safety guards
- READ-only enforcement (blocks INSERT, UPDATE, DELETE, DROP, etc.)
- Table allow/deny lists
- Row limits and query timeouts
- SQL injection prevention

### Query Explanation
- `explain_query` - Show execution plan without running the query
- `analyze_query` - Execute and show actual statistics (EXPLAIN ANALYZE)
- Multiple output formats: TEXT, JSON, YAML, XML

### Connection Management
- `list_connections` - List configured database profiles
- `test_connection` - Verify database connectivity
- Multi-database support with connection pooling (HikariCP)

### Observability
- Structured JSON logging for audit trails
- Per-query metrics (duration, row count, status)
- Per-connection statistics

## Supported Databases

| Database | Status |
|----------|--------|
| PostgreSQL | Supported |
| MySQL / MariaDB | Supported |
| SQLite | Supported |

## Quick Start

### Build

```bash
./mvnw clean package -DskipTests
```

### Run with STDIO (for Claude Desktop / MCP Inspector)

```bash
java -jar target/sql-mcp-server-1.0.0-SNAPSHOT.jar
```

### Run with HTTP/SSE (for server deployment)

```bash
java -jar target/sql-mcp-server-1.0.0-SNAPSHOT.jar \
  --sql-mcp.transport=http \
  --server.port=8080
```

## Configuration

Create `application.yml` or pass via command line:

```yaml
sql-mcp:
  transport: stdio  # or 'http' for SSE server mode

  connections:
    - name: production
      type: postgresql
      host: localhost
      port: 5432
      database: myapp
      username: ${DB_USER}
      password: ${DB_PASS}
      read-only: true

    - name: analytics
      type: postgresql
      host: analytics.example.com
      port: 5432
      database: warehouse
      username: ${ANALYTICS_USER}
      password: ${ANALYTICS_PASS}
      read-only: true

  query:
    default-timeout-ms: 30000
    default-row-limit: 1000
    max-row-limit: 10000

  tables:
    allow-list: []  # Empty = all allowed
    deny-list:
      - "pg_*"
      - "information_schema.*"
```

## Claude Desktop Integration

Add to `~/.config/claude/claude_desktop_config.json`:

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

## MCP Tools Reference

### list_connections
Lists all configured database connection profiles.

**Arguments:** None

**Returns:** Connection names, types, hosts, and read-only status.

---

### test_connection
Tests connectivity to a database.

**Arguments:**
- `connection` (required): Connection profile name

**Returns:** Success status and database version info.

---

### list_tables
Lists all tables and views in a database.

**Arguments:**
- `connection` (required): Connection profile name
- `schema` (optional): Schema name pattern (supports `%` wildcard)

**Returns:** Table names, types (TABLE/VIEW), schemas, and comments.

---

### describe_table
Describes a table's structure.

**Arguments:**
- `connection` (required): Connection profile name
- `table` (required): Table name
- `schema` (optional): Schema name

**Returns:** Column details including name, type, nullable, primary key, default value.

---

### list_foreign_keys
Lists foreign key relationships for a table.

**Arguments:**
- `connection` (required): Connection profile name
- `table` (required): Table name
- `schema` (optional): Schema name

**Returns:** FK names, source/target columns, ON UPDATE/DELETE actions.

---

### sample_rows
Retrieves sample data from a table.

**Arguments:**
- `connection` (required): Connection profile name
- `table` (required): Table name
- `schema` (optional): Schema name
- `limit` (optional): Max rows (default 10, max 100)

**Returns:** Sample rows as JSON objects.

---

### execute_query
Executes a SELECT query with safety guards.

**Arguments:**
- `connection` (required): Connection profile name
- `query` (required): SQL SELECT query
- `limit` (optional): Max rows to return

**Returns:** Query results, column metadata, execution time.

**Safety:** Blocks non-SELECT statements, enforces table deny lists.

---

### explain_query
Shows the execution plan for a query without running it.

**Arguments:**
- `connection` (required): Connection profile name
- `query` (required): SQL SELECT query
- `format` (optional): Output format (text, json, yaml, xml)

**Returns:** Estimated execution plan with costs.

---

### analyze_query
Executes a query and shows actual execution statistics.

**Arguments:**
- `connection` (required): Connection profile name
- `query` (required): SQL SELECT query
- `format` (optional): Output format (text, json, yaml, xml)

**Returns:** Actual execution plan with timing and buffer usage.

**Warning:** Actually executes the query.

## Testing

Integration tests use Testcontainers with PostgreSQL and the MCP Java SDK client:

```bash
./mvnw test
```

Tests cover:
- Connection management
- Schema introspection
- Query execution with safety guards
- Query explanation
- Cross-database isolation

## Project Structure

```
src/main/java/co/fanki/sqlmcp/
├── SqlMcpServerApplication.java
├── config/
│   └── McpServerConfig.java
├── connection/
│   ├── domain/
│   │   ├── ConnectionProfile.java
│   │   ├── ConnectionProfileRepository.java
│   │   ├── DatabaseType.java
│   │   └── DataSourceFactory.java
│   └── application/
│       ├── ListConnectionsTool.java
│       └── TestConnectionTool.java
├── introspection/
│   ├── domain/
│   │   ├── ColumnMetadata.java
│   │   ├── ForeignKeyMetadata.java
│   │   ├── SchemaIntrospector.java
│   │   └── TableMetadata.java
│   └── application/
│       └── IntrospectionTools.java
├── query/
│   ├── domain/
│   │   ├── QueryExecutor.java
│   │   ├── QueryExplainer.java
│   │   ├── QueryGuard.java
│   │   └── QueryResult.java
│   └── application/
│       ├── ExecuteQueryTool.java
│       └── ExplainQueryTools.java
└── observability/
    └── domain/
        ├── QueryAuditLog.java
        └── QueryLogger.java
```

## Technology Stack

| Component | Technology |
|-----------|------------|
| Language | Java 21 |
| Framework | Spring Boot 3.4 |
| MCP SDK | io.modelcontextprotocol.sdk 0.12.1 |
| Connection Pool | HikariCP |
| Logging | Logback with JSON support |
| Testing | JUnit 5, Testcontainers, MCP Client |

## Roadmap

- [x] MCP scaffolding (STDIO + HTTP/SSE)
- [x] PostgreSQL driver
- [x] MySQL/MariaDB driver
- [x] SQLite driver
- [x] Schema introspection tools
- [x] Safe query executor
- [x] Query explanation mode
- [x] Connection profiles
- [x] Structured logging
- [x] Integration tests
- [ ] Prometheus metrics endpoint
- [ ] Query cost guards (pre-flight EXPLAIN check)
- [ ] Result pagination
- [ ] Query simulation mode (schema-only)

## License

MIT
