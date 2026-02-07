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

### 3. Add to Claude Desktop

Edit `~/.config/claude/claude_desktop_config.json`:

**Local (STDIO):**
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

Integration tests use Testcontainers with PostgreSQL.

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
