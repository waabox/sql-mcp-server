# sql-mcp-server

A minimal, fast MCP server that exposes SQL databases to LLMs through a clean, typed and controllable protocol.

Built with **Java 21** and **Spring Boot 3.4**, using the official [MCP Java SDK](https://modelcontextprotocol.io/sdk/java/mcp-overview).

## Table of Contents

- [Features](#features)
- [Supported Databases](#supported-databases)
- [Installation](#installation)
- [Configuration](#configuration)
- [Deployment](#deployment)
- [Claude Desktop Integration](#claude-desktop-integration)
- [MCP Tools Reference](#mcp-tools-reference)
- [Testing](#testing)
- [Project Structure](#project-structure)
- [Technology Stack](#technology-stack)
- [Roadmap](#roadmap)

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

## Installation

### Prerequisites

- Java 21 or later
- Maven 3.8+ (or use included wrapper)

### Build from Source

```bash
# Clone the repository
git clone https://github.com/waabox/sql-mcp-server.git
cd sql-mcp-server

# Build the JAR
./mvnw clean package -DskipTests

# The JAR will be at target/sql-mcp-server-1.0.0-SNAPSHOT.jar
```

### Run Locally

#### STDIO Mode (for Claude Desktop / MCP Inspector)

```bash
java -jar target/sql-mcp-server-1.0.0-SNAPSHOT.jar \
  --spring.config.location=./application.yml
```

#### HTTP/SSE Mode (for server deployment)

```bash
java -jar target/sql-mcp-server-1.0.0-SNAPSHOT.jar \
  --sql-mcp.transport=http \
  --server.port=8080
```

## Configuration

### Configuration File

Create `application.yml`:

```yaml
sql-mcp:
  transport: stdio  # 'stdio' for CLI, 'http' for server mode

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
      type: mysql
      host: analytics.example.com
      port: 3306
      database: warehouse
      username: ${ANALYTICS_USER}
      password: ${ANALYTICS_PASS}
      read-only: true

  query:
    default-timeout-ms: 30000
    default-row-limit: 1000
    max-row-limit: 10000

  tables:
    allow-list: []  # Empty = all tables allowed
    deny-list:
      - "pg_*"
      - "information_schema.*"
      - "*_audit"
      - "credentials"

# Server configuration (HTTP mode only)
server:
  port: 8080

# Logging configuration
logging:
  level:
    root: INFO
    co.fanki.sqlmcp: DEBUG
    audit.query: INFO
```

### Environment Variables

All configuration can be overridden via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `SQL_MCP_TRANSPORT` | Transport mode (`stdio` or `http`) | `stdio` |
| `SERVER_PORT` | HTTP server port | `8080` |
| `DB_USER` | Database username | - |
| `DB_PASS` | Database password | - |
| `LOG_PATH` | Log file directory | `logs` |

### Connection Types

| Type | Value | Default Port |
|------|-------|--------------|
| PostgreSQL | `postgresql` | 5432 |
| MySQL | `mysql` | 3306 |
| MariaDB | `mariadb` | 3306 |
| SQLite | `sqlite` | - |

### SQLite Configuration

```yaml
sql-mcp:
  connections:
    - name: local-db
      type: sqlite
      database: /path/to/database.db
      read-only: true
```

## Deployment

### Docker

The project includes a multi-stage Dockerfile that builds the application and creates an optimized runtime image.

#### Build and Run

```bash
# Build image
docker build -t sql-mcp-server:latest .

# Run with environment variables
docker run -d \
  --name sql-mcp-server \
  -p 8080:8080 \
  -e SQL_MCP_TRANSPORT=http \
  -e DB_USER=myuser \
  -e DB_PASS=mypassword \
  -v ./config:/app/config \
  sql-mcp-server:latest \
  --spring.config.location=/app/config/application.yml
```

### Docker Compose

```yaml
version: '3.8'

services:
  sql-mcp-server:
    image: sql-mcp-server:latest
    build: .
    ports:
      - "8080:8080"
    environment:
      - SQL_MCP_TRANSPORT=http
      - SERVER_PORT=8080
      - DB_USER=${DB_USER}
      - DB_PASS=${DB_PASS}
    volumes:
      - ./config:/app/config:ro
      - ./logs:/app/logs
    command: ["--spring.config.location=/app/config/application.yml"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    restart: unless-stopped
```

### Kubernetes

#### ConfigMap

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: sql-mcp-config
  namespace: mcp
data:
  application.yml: |
    sql-mcp:
      transport: http
      connections:
        - name: production
          type: postgresql
          host: postgres.database.svc.cluster.local
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

    server:
      port: 8080

    logging:
      level:
        root: INFO
```

#### Secret

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: sql-mcp-secrets
  namespace: mcp
type: Opaque
stringData:
  DB_USER: "readonly_user"
  DB_PASS: "your-secure-password"
```

#### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: sql-mcp-server
  namespace: mcp
  labels:
    app: sql-mcp-server
spec:
  replicas: 2
  selector:
    matchLabels:
      app: sql-mcp-server
  template:
    metadata:
      labels:
        app: sql-mcp-server
    spec:
      containers:
        - name: sql-mcp-server
          image: sql-mcp-server:latest
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: SQL_MCP_TRANSPORT
              value: "http"
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: sql-mcp-secrets
                  key: DB_USER
            - name: DB_PASS
              valueFrom:
                secretKeyRef:
                  name: sql-mcp-secrets
                  key: DB_PASS
          volumeMounts:
            - name: config
              mountPath: /app/config
              readOnly: true
          args:
            - "--spring.config.location=/app/config/application.yml"
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: config
          configMap:
            name: sql-mcp-config
```

#### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: sql-mcp-server
  namespace: mcp
spec:
  selector:
    app: sql-mcp-server
  ports:
    - port: 80
      targetPort: 8080
      name: http
  type: ClusterIP
```

#### Ingress (optional)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: sql-mcp-ingress
  namespace: mcp
  annotations:
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - sql-mcp.example.com
      secretName: sql-mcp-tls
  rules:
    - host: sql-mcp.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: sql-mcp-server
                port:
                  number: 80
```

### Helm Chart Values (example)

```yaml
# values.yaml
replicaCount: 2

image:
  repository: sql-mcp-server
  tag: latest
  pullPolicy: IfNotPresent

service:
  type: ClusterIP
  port: 80

ingress:
  enabled: true
  host: sql-mcp.example.com

resources:
  requests:
    memory: 256Mi
    cpu: 100m
  limits:
    memory: 512Mi
    cpu: 500m

# Health check configuration
healthCheck:
  path: /health
  port: 8080

livenessProbe:
  enabled: true
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3

readinessProbe:
  enabled: true
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3

config:
  transport: http
  connections:
    - name: production
      type: postgresql
      host: postgres.database.svc.cluster.local
      port: 5432
      database: appdb

secrets:
  dbUser: readonly_user
  dbPass: ""  # Set via --set secrets.dbPass=xxx
```

### Production Recommendations

1. **Security**
   - Always use `read-only: true` for database connections
   - Configure table deny-lists to exclude sensitive tables
   - Use Kubernetes Secrets or external secret managers for credentials
   - Enable TLS for HTTP transport in production

2. **High Availability**
   - Run at least 2 replicas
   - Configure proper health checks
   - Use connection pooling (HikariCP is enabled by default)

3. **Monitoring**
   - Mount logs volume for centralized log collection
   - Configure structured JSON logging for production
   - Set appropriate log levels

4. **Resource Management**
   - Set memory limits based on expected query complexity
   - Configure query timeouts to prevent runaway queries
   - Use row limits to control response sizes

## Claude Desktop Integration

### Local Mode (STDIO)

For local development, add to `~/.config/claude/claude_desktop_config.json` (macOS/Linux) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "sql": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/sql-mcp-server-1.0.0-SNAPSHOT.jar",
        "--spring.config.location=/path/to/application.yml"
      ],
      "env": {
        "DB_USER": "your_username",
        "DB_PASS": "your_password"
      }
    }
  }
}
```

### Remote Mode (HTTP/SSE)

When connecting to a deployed SQL MCP server running in HTTP mode:

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

For internal Kubernetes deployments:

```json
{
  "mcpServers": {
    "sql": {
      "url": "http://sql-mcp-server.mcp.svc.cluster.local/sse",
      "transport": "sse"
    }
  }
}
```

**Note:** When using HTTP/SSE transport, ensure the server is accessible from the client and proper authentication/TLS is configured for production environments.

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
- [x] Health check endpoint
- [ ] Prometheus metrics endpoint
- [ ] Query cost guards (pre-flight EXPLAIN check)
- [ ] Result pagination
- [ ] Query simulation mode (schema-only)

## License

MIT
