# Usage Scenarios

Real-world examples of using sql-mcp-server with Claude.

## Scenario 1: Data Analyst Exploring a Warehouse

An analyst needs to understand the schema of a data warehouse and run ad-hoc queries.

### Setup

```yaml
# application.yml
sql-mcp:
  connections:
    - name: warehouse
      type: postgresql
      host: warehouse.internal.company.com
      port: 5432
      database: analytics
      username: ${WAREHOUSE_USER}
      password: ${WAREHOUSE_PASS}
      read-only: true

  tables:
    deny-list:
      - "pg_*"
      - "information_schema.*"
      - "*_pii"           # Exclude PII tables
      - "salary_*"        # Exclude salary data
```

### Conversation Example

**User:** "I need to understand our sales data. What tables do we have?"

**Claude calls `list_tables`:**
```json
{
  "connection": "warehouse"
}
```

**Response:**
```json
{
  "tables": [
    {"name": "orders", "type": "TABLE", "schema": "public"},
    {"name": "order_items", "type": "TABLE", "schema": "public"},
    {"name": "products", "type": "TABLE", "schema": "public"},
    {"name": "customers", "type": "TABLE", "schema": "public"},
    {"name": "daily_sales_summary", "type": "VIEW", "schema": "reports"}
  ]
}
```

**User:** "Show me the structure of the orders table"

**Claude calls `describe_table`:**
```json
{
  "connection": "warehouse",
  "table": "orders"
}
```

**Response:**
```json
{
  "columns": [
    {"name": "id", "type": "bigint", "nullable": false, "primaryKey": true},
    {"name": "customer_id", "type": "bigint", "nullable": false},
    {"name": "order_date", "type": "timestamp", "nullable": false},
    {"name": "total_amount", "type": "numeric(12,2)", "nullable": false},
    {"name": "status", "type": "varchar(20)", "nullable": false}
  ]
}
```

**User:** "What were our top 10 selling days last month?"

**Claude calls `execute_query`:**
```json
{
  "connection": "warehouse",
  "query": "SELECT DATE(order_date) as day, COUNT(*) as orders, SUM(total_amount) as revenue FROM orders WHERE order_date >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') AND order_date < DATE_TRUNC('month', CURRENT_DATE) GROUP BY DATE(order_date) ORDER BY revenue DESC LIMIT 10"
}
```

**Response:**
```json
{
  "columns": ["day", "orders", "revenue"],
  "rows": [
    {"day": "2025-01-15", "orders": 1523, "revenue": 245832.50},
    {"day": "2025-01-22", "orders": 1456, "revenue": 231445.00}
  ],
  "rowCount": 10,
  "executionTimeMs": 145
}
```

---

## Scenario 2: SRE Investigating Production Metrics

An SRE needs to query a metrics/logs database to investigate an incident.

### Setup

```yaml
# application.yml
sql-mcp:
  connections:
    - name: metrics-db
      type: postgresql
      host: metrics.internal.company.com
      port: 5432
      database: observability
      username: ${METRICS_USER}
      password: ${METRICS_PASS}
      read-only: true

  query:
    default-timeout-ms: 60000   # Longer timeout for metrics queries
    default-row-limit: 500
    max-row-limit: 5000

  tables:
    allow-list:
      - "http_requests"
      - "error_logs"
      - "service_health"
      - "deployment_events"
```

### Conversation Example

**User:** "We're seeing increased latency on the checkout service. Show me the p99 latency over the last hour, broken down by 5-minute intervals."

**Claude calls `execute_query`:**
```json
{
  "connection": "metrics-db",
  "query": "SELECT DATE_TRUNC('minute', timestamp) - (EXTRACT(MINUTE FROM timestamp)::int % 5) * INTERVAL '1 minute' as interval, PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY response_time_ms) as p99_ms, COUNT(*) as request_count FROM http_requests WHERE service = 'checkout' AND timestamp >= NOW() - INTERVAL '1 hour' GROUP BY 1 ORDER BY 1"
}
```

**User:** "Are there any error spikes correlating with the latency?"

**Claude calls `execute_query`:**
```json
{
  "connection": "metrics-db",
  "query": "SELECT DATE_TRUNC('minute', timestamp) - (EXTRACT(MINUTE FROM timestamp)::int % 5) * INTERVAL '1 minute' as interval, COUNT(*) FILTER (WHERE status_code >= 500) as server_errors, COUNT(*) FILTER (WHERE status_code >= 400 AND status_code < 500) as client_errors, COUNT(*) as total FROM http_requests WHERE service = 'checkout' AND timestamp >= NOW() - INTERVAL '1 hour' GROUP BY 1 ORDER BY 1"
}
```

**User:** "Was there a deployment around that time?"

**Claude calls `execute_query`:**
```json
{
  "connection": "metrics-db",
  "query": "SELECT deployed_at, version, deployer, rollback FROM deployment_events WHERE service = 'checkout' AND deployed_at >= NOW() - INTERVAL '2 hours' ORDER BY deployed_at DESC"
}
```

**Response:**
```json
{
  "columns": ["deployed_at", "version", "deployer", "rollback"],
  "rows": [
    {"deployed_at": "2025-02-07T14:23:00Z", "version": "v2.3.1", "deployer": "ci-bot", "rollback": false}
  ],
  "rowCount": 1,
  "executionTimeMs": 23
}
```

**User:** "Show me the error logs around that deployment time"

**Claude calls `execute_query`:**
```json
{
  "connection": "metrics-db",
  "query": "SELECT timestamp, level, message, trace_id FROM error_logs WHERE service = 'checkout' AND timestamp BETWEEN '2025-02-07T14:20:00Z' AND '2025-02-07T14:30:00Z' ORDER BY timestamp LIMIT 50"
}
```

---

## Scenario 3: Developer Debugging a Data Issue

A developer needs to understand why a specific user's data looks wrong.

### Setup

```yaml
# application.yml (dev environment)
sql-mcp:
  connections:
    - name: dev-db
      type: postgresql
      host: localhost
      port: 5432
      database: myapp_dev
      username: developer
      password: devpass
      read-only: true

  tables:
    deny-list:
      - "pg_*"
      - "api_keys"
      - "password_*"
```

### Conversation Example

**User:** "User 12345 says their subscription shows as expired but they paid. Can you check?"

**Claude calls `execute_query`:**
```json
{
  "connection": "dev-db",
  "query": "SELECT u.id, u.email, s.plan, s.status, s.expires_at, s.cancelled_at FROM users u JOIN subscriptions s ON s.user_id = u.id WHERE u.id = 12345"
}
```

**User:** "Check their payment history"

**Claude calls `execute_query`:**
```json
{
  "connection": "dev-db",
  "query": "SELECT p.id, p.amount, p.currency, p.status, p.created_at, p.provider_reference FROM payments p WHERE p.user_id = 12345 ORDER BY p.created_at DESC LIMIT 10"
}
```

**User:** "Is there a foreign key relationship between subscriptions and payments?"

**Claude calls `list_foreign_keys`:**
```json
{
  "connection": "dev-db",
  "table": "subscriptions"
}
```

---

## Tips for Effective Use

1. **Start with schema exploration** - Use `list_tables` and `describe_table` before writing queries
2. **Check relationships** - Use `list_foreign_keys` to understand how tables connect
3. **Preview data** - Use `sample_rows` to see what data looks like before complex queries
4. **Optimize queries** - Use `explain_query` to check execution plans for slow queries
5. **Set appropriate limits** - Always include LIMIT in exploratory queries
