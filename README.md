# sql-mcp-server
A minimal, fast, experimental MCP server that exposes SQL databases to LLMs through a clean, typed and controllable protocol.

This project is a work in progress — the goal is to make SQL access **LLM-friendly**, safe, auditable and easy to plug into any agent workflow (Claude, Cursor, custom toolchains, automation pipelines, etc.).

## ✨ What this project aims to provide
### ✔ Unified SQL access layer
A single MCP server that can connect to multiple SQL engines:

- PostgreSQL
- MySQL / MariaDB
- SQLite
- (future) Snowflake / BigQuery
- (future) SQL Server

## 🚀 Features (planned)
### 1. Schema Introspection
Expose metadata such as tables, columns, types, foreign keys, views, and sample rows.

### 2. Safe Query Execution
READ-only by default, table allow/deny lists, timeouts, row limits, and cost guards.

### 3. Query Explanation Mode
Support for EXPLAIN, optional ANALYZE, and plan summaries.

### 4. Result Normalization
Consistent JSON formatting with metadata, paging, and truncation markers.

### 5. Connection Profiles
Config-driven multi-connection support.

### 6. Query Simulation Mode
Future offline mode for schema-only databases.

### 7. Observability Hooks
Structured logs, auditing, histograms, and Prometheus metrics.

## 🧠 Why build this?
SQL is the strongest source of truth in any system. MCP makes structured tooling possible for LLMs, so this project aims to make SQL access safe and predictable.

## 🏗 Current Status
Early development — API and architecture taking form.

## 📦 Roadmap
- MCP scaffolding
- PostgreSQL driver
- Introspection endpoints
- Safe query executor
- Query explain mode
- Config profiles
- Logging and tracing
- CLI wrapper
- MySQL driver
- SQLite mode
- Test suite
- Docs

## 🤝 Contributing
Early R&D. PRs welcome once the API stabilizes.

## 📜 License
MIT
