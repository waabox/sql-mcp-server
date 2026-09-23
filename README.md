![CI](https://github.com/waabox/sql-mcp-server/actions/workflows/ci.yml/badge.svg) ![Java](https://img.shields.io/badge/Java-21-blue.svg) ![Maven](https://img.shields.io/badge/Maven-3.9-orange.svg) [![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE) ![Docker Image](https://img.shields.io/badge/docker-ready-blue) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen) ![MCP Compatible](https://img.shields.io/badge/MCP-Server-blueviolet)

# SQL MCP Server

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
claude mcp add sql --transport sse http://sql-mcp.example.com/sse \
  --header "Authorization: Bearer ${SQL_MCP_AUTH_TOKEN}"
```

Or in `.mcp.json`:
```json
{
  "mcpServers": {
    "sql": {
      "url": "http://sql-mcp.example.com/sse",
      "transport": "sse",
      "headers": { "Authorization": "Bearer ${SQL_MCP_AUTH_TOKEN}" }
    }
  }
}
```

The bearer token is required when the server sets `sql-mcp.http.auth-token`
(see [Safety](#safety)).

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
  "truncated": false,
  "executionTimeMs": 45
}
```

| Field | Description |
|-------|-------------|
| `columns` | Column metadata (name and SQL type) |
| `rows` | Result rows as JSON objects, keys in column order. Duplicate labels get a suffix (`id`, `id_2`) |
| `rowCount` | Number of rows returned |
| `truncated` | `true` when more rows than `limit` exist. The total is not counted, to avoid reading the whole result |
| `executionTimeMs` | Query execution time in milliseconds |

## Supported Databases

| Database       | Status    |
|----------------|-----------|
| PostgreSQL     | Supported |
| MySQL          | Supported |
| MariaDB        | Supported |
| SQLite         | Supported |

## Safety

The server is designed for read-only access to production databases. Protection
works in layers:

- **Read-only database sessions**: Every connection is read-only and runs in a
  transaction that is always rolled back, whatever the profile says
  (`read-only: false` is ignored with a warning). PostgreSQL runs `BEGIN READ ONLY`;
  MySQL/MariaDB use `SET SESSION TRANSACTION READ ONLY`; SQLite opens the file in
  read-only mode.

- **Server-side timeouts**: Each query gets a statement timeout (`default-timeout-ms`,
  capped by `max-timeout-ms`) and a lock timeout (`lock-timeout-ms`). They are
  applied by the database, not just the client. On PostgreSQL they are set with
  `SET LOCAL`, which also works behind PgBouncer in transaction mode.

- **Bounded reads**: Row limits are enforced by the driver with a cursor. The
  server stops producing rows once the limit is reached, so a `SELECT *` on a
  huge table returns quickly.

- **Statement validation**: The query is tokenized with each engine's quoting and
  comment rules. Keywords inside string literals, quoted identifiers or comments
  are ignored, so `WHERE action = 'DELETE' OR type = 'x'` and `UNION ALL` work.
  The validator rejects:
  - more than one statement;
  - anything that does not start with `SELECT` or `WITH`;
  - data-modifying keywords anywhere, including writable CTEs and `SELECT ... INTO`;
  - row-locking clauses (`FOR UPDATE`, `FOR SHARE`, `LOCK IN SHARE MODE`);
  - functions that affect other sessions or the server, such as
    `pg_terminate_backend`, `pg_cancel_backend`, `set_config`, `pg_sleep`,
    advisory locks, `nextval`, `pg_stat_reset`, file access, `dblink` and
    `query_to_xml`.

  The query text is sent to the database unchanged.

- **Table access control**: Allow/deny lists apply to every table in `FROM`/`JOIN`
  clauses at any depth (subqueries, CTEs, comma joins). They also apply to
  `list_tables`, `describe_table`, `list_foreign_keys` and `sample_rows`. System
  catalogs such as `pg_stat_activity`, `pg_locks` and `information_schema` are
  allowed by default because debugging needs them.

- **Audit log**: Every query attempt is written to `logs/audit.log` as one JSON
  line: connection, query, status (`SUCCESS`, `FAILURE`, `VALIDATION_ERROR`),
  duration and row count.

- **HTTP authentication**: With the HTTP transport, set `sql-mcp.http.auth-token`
  (or `SQL_MCP_AUTH_TOKEN`) to require `Authorization: Bearer <token>` on every
  request except `/health`. Without a token the server runs unauthenticated and
  logs a warning at startup.

**Important:** These layers do not replace database permissions. Use a dedicated
database user that can only read what Claude needs. On PostgreSQL:

```sql
CREATE ROLE claude_ro LOGIN PASSWORD '...';
GRANT CONNECT ON DATABASE appdb TO claude_ro;
GRANT USAGE ON SCHEMA public TO claude_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO claude_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO claude_ro;
-- Optional: see every session's query text in pg_stat_activity / pg_stat_statements
GRANT pg_read_all_stats TO claude_ro;
```

Never use a superuser, and never grant `pg_signal_backend`, `pg_write_server_files`
or `pg_read_server_files` to this role. Point the connection at a read replica
when you have one.

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
    default-timeout-ms: 30000   # per-query default
    max-timeout-ms: 120000      # upper bound for the per-call "timeout" argument
    lock-timeout-ms: 5000       # fail instead of waiting on locks
    default-row-limit: 1000
    max-row-limit: 10000

  tables:
    # '*' is a wildcard. A pattern without a dot matches the table name in any
    # schema; a pattern with a dot matches the schema-qualified name.
    deny-list:
      - "*_audit"
      - "credentials"
      - "secret.*"

  http:
    auth-token: ${SQL_MCP_AUTH_TOKEN:}   # HTTP transport only
```

### Multiple Database Connections

The server supports connecting to multiple databases simultaneously, even across
different database engines. Each connection is identified by a unique `name` that
you reference when calling any MCP tool.

```yaml
sql-mcp:
  transport: stdio

  connections:
    # PostgreSQL production database
    - name: production
      type: postgresql
      host: db.example.com
      port: 5432
      database: appdb
      username: ${PROD_DB_USER}
      password: ${PROD_DB_PASS}
      schema: public
      read-only: true

    # PostgreSQL analytics database
    - name: analytics
      type: postgresql
      host: analytics-db.example.com
      port: 5432
      database: analyticsdb
      username: ${ANALYTICS_DB_USER}
      password: ${ANALYTICS_DB_PASS}
      schema: reporting
      read-only: true

    # MySQL legacy system
    - name: legacy
      type: mysql
      host: legacy-db.internal
      port: 3306
      database: legacy_app
      username: ${LEGACY_DB_USER}
      password: ${LEGACY_DB_PASS}
      read-only: true

    # Local SQLite file
    - name: local-cache
      type: sqlite
      database: /data/cache.db
      read-only: true
```

#### Connection Properties

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `name` | string | Yes | - | Unique identifier used in tool calls |
| `type` | string | Yes | - | Database engine: `postgresql`, `mysql`, `mariadb`, `sqlite` |
| `host` | string | Yes* | - | Database host (*not required for SQLite) |
| `port` | int | No | Auto | Default: 5432 (PostgreSQL), 3306 (MySQL/MariaDB) |
| `database` | string | Yes | - | Database name or file path (SQLite) |
| `username` | string | Yes* | - | Database user (*not required for SQLite) |
| `password` | string | Yes* | - | Database password (*not required for SQLite) |
| `schema` | string | No | Engine default | Default schema for queries |
| `read-only` | boolean | No | `true` | Ignored: every connection is read-only. `false` logs a warning |

#### Using Multiple Connections

Every MCP tool accepts a `connection` parameter to target a specific database:

```
"List all tables in the analytics database"
→ list_tables({ "connection": "analytics" })

"Show me orders from the production database"
→ execute_query({ "connection": "production", "query": "SELECT * FROM orders LIMIT 10" })

"Describe the users table in the legacy MySQL database"
→ describe_table({ "connection": "legacy", "table": "users" })
```

Connections are fully isolated — each database maintains its own connection pool
and queries are routed to the correct engine. You can switch between connections
freely within the same session.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SQL_MCP_TRANSPORT` | `stdio` or `http` | `stdio` |
| `SQL_MCP_AUTH_TOKEN` | Bearer token required by the HTTP transport | - (unauthenticated) |
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

**Test coverage (114 tests):**
- Connection management (list, test, unknown connection)
- Schema introspection (list tables, describe, foreign keys, sample rows)
- Query execution (SELECT, JOIN, aggregates, row limits)
- Safety guards (blocks INSERT, DELETE, DROP, writable CTEs, `FOR UPDATE`, dangerous functions)
- Production safety: read-only transactions, server-side timeouts, bounded reads, table deny list, audit log
- Read-only enforcement at the database level on PostgreSQL, MySQL, MariaDB and SQLite
- Query validator unit tests (dialect-aware tokenization, table extraction)
- Query explanation (EXPLAIN, ANALYZE, JSON format)
- **Cross-database operations**: Tests switch between `ecommerce` and `hr` connections in the same session, verifying database isolation (tables from one DB don't appear in another) and correct query routing across multiple databases

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
