package co.fanki.sqlmcp.introspection.domain;

import co.fanki.sqlmcp.connection.domain.DataSourceFactory;
import co.fanki.sqlmcp.introspection.domain.ForeignKeyMetadata.ReferentialAction;
import co.fanki.sqlmcp.introspection.domain.TableMetadata.TableType;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain service for introspecting database schema metadata.
 *
 * <p>Uses JDBC DatabaseMetaData to retrieve information about tables,
 * columns, and foreign key relationships.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class SchemaIntrospector {

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW", "MATERIALIZED VIEW"};

    private final DataSourceFactory dataSourceFactory;

    /**
     * Creates a new SchemaIntrospector.
     *
     * @param dataSourceFactory the factory for obtaining data sources
     */
    public SchemaIntrospector(final DataSourceFactory dataSourceFactory) {
        this.dataSourceFactory = Objects.requireNonNull(dataSourceFactory);
    }

    /**
     * Lists all tables in the specified schema.
     *
     * @param connectionName the connection profile name
     * @param schemaPattern the schema pattern (null for all schemas)
     * @return list of table metadata
     * @throws IntrospectionException if database access fails
     */
    public List<TableMetadata> listTables(
            final String connectionName,
            final String schemaPattern) {

        DataSource dataSource = dataSourceFactory.getDataSource(connectionName);
        List<TableMetadata> tables = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();

            try (ResultSet rs = metaData.getTables(catalog, schemaPattern, "%", TABLE_TYPES)) {
                while (rs.next()) {
                    TableMetadata table = TableMetadata.create(
                            rs.getString("TABLE_CAT"),
                            rs.getString("TABLE_SCHEM"),
                            rs.getString("TABLE_NAME"),
                            TableType.fromJdbcType(rs.getString("TABLE_TYPE")),
                            rs.getString("REMARKS")
                    );
                    tables.add(table);
                }
            }
        } catch (SQLException e) {
            throw new IntrospectionException("Failed to list tables: " + e.getMessage(), e);
        }

        return tables;
    }

    /**
     * Describes a table's columns.
     *
     * @param connectionName the connection profile name
     * @param schemaName the schema name (null for default)
     * @param tableName the table name
     * @return list of column metadata
     * @throws IntrospectionException if database access fails
     */
    public List<ColumnMetadata> describeTable(
            final String connectionName,
            final String schemaName,
            final String tableName) {

        DataSource dataSource = dataSourceFactory.getDataSource(connectionName);
        List<ColumnMetadata> columns = new ArrayList<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();

            // Get primary key columns
            Set<String> primaryKeys = getPrimaryKeyColumns(metaData, catalog, schemaName, tableName);

            try (ResultSet rs = metaData.getColumns(catalog, schemaName, tableName, "%")) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    String isAutoIncrement = rs.getString("IS_AUTOINCREMENT");

                    ColumnMetadata column = ColumnMetadata.builder()
                            .name(columnName)
                            .dataType(rs.getString("TYPE_NAME"))
                            .jdbcType(rs.getInt("DATA_TYPE"))
                            .size(rs.getInt("COLUMN_SIZE"))
                            .decimalDigits(rs.getInt("DECIMAL_DIGITS"))
                            .nullable("YES".equals(rs.getString("IS_NULLABLE")))
                            .defaultValue(rs.getString("COLUMN_DEF"))
                            .primaryKey(primaryKeys.contains(columnName))
                            .autoIncrement("YES".equals(isAutoIncrement))
                            .comment(rs.getString("REMARKS"))
                            .ordinalPosition(rs.getInt("ORDINAL_POSITION"))
                            .build();

                    columns.add(column);
                }
            }
        } catch (SQLException e) {
            throw new IntrospectionException("Failed to describe table: " + e.getMessage(), e);
        }

        return columns;
    }

    /**
     * Lists foreign keys for a table.
     *
     * @param connectionName the connection profile name
     * @param schemaName the schema name (null for default)
     * @param tableName the table name
     * @return list of foreign key metadata
     * @throws IntrospectionException if database access fails
     */
    public List<ForeignKeyMetadata> listForeignKeys(
            final String connectionName,
            final String schemaName,
            final String tableName) {

        DataSource dataSource = dataSourceFactory.getDataSource(connectionName);
        Map<String, ForeignKeyMetadata.Builder> fkBuilders = new HashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String catalog = connection.getCatalog();

            try (ResultSet rs = metaData.getImportedKeys(catalog, schemaName, tableName)) {
                while (rs.next()) {
                    String fkName = rs.getString("FK_NAME");
                    String fkTableName = rs.getString("FKTABLE_NAME");
                    String pkTableName = rs.getString("PKTABLE_NAME");
                    String key = fkName != null ? fkName : fkTableName + "_" + pkTableName;

                    ForeignKeyMetadata.Builder builder = fkBuilders.get(key);
                    if (builder == null) {
                        builder = ForeignKeyMetadata.builder()
                                .name(fkName)
                                .sourceSchema(rs.getString("FKTABLE_SCHEM"))
                                .sourceTable(fkTableName)
                                .targetSchema(rs.getString("PKTABLE_SCHEM"))
                                .targetTable(pkTableName)
                                .onUpdate(ReferentialAction.fromJdbcCode(rs.getInt("UPDATE_RULE")))
                                .onDelete(ReferentialAction.fromJdbcCode(rs.getInt("DELETE_RULE")));
                        fkBuilders.put(key, builder);
                    }

                    builder.addSourceColumn(rs.getString("FKCOLUMN_NAME"));
                    builder.addTargetColumn(rs.getString("PKCOLUMN_NAME"));
                }
            }
        } catch (SQLException e) {
            throw new IntrospectionException("Failed to list foreign keys: " + e.getMessage(), e);
        }

        List<ForeignKeyMetadata> foreignKeys = new ArrayList<>();
        fkBuilders.values().forEach(builder -> foreignKeys.add(builder.build()));
        return foreignKeys;
    }

    /**
     * Retrieves sample rows from a table.
     *
     * @param connectionName the connection profile name
     * @param schemaName the schema name (null for default)
     * @param tableName the table name
     * @param limit maximum number of rows to return
     * @return list of row maps (column name -> value)
     * @throws IntrospectionException if database access fails
     */
    public List<Map<String, Object>> sampleRows(
            final String connectionName,
            final String schemaName,
            final String tableName,
            final int limit) {

        DataSource dataSource = dataSourceFactory.getDataSource(connectionName);
        List<Map<String, Object>> rows = new ArrayList<>();

        String qualifiedName = schemaName != null && !schemaName.isBlank()
                ? quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName)
                : quoteIdentifier(tableName);

        // Use parameterized limit to prevent SQL injection
        String sql = String.format("SELECT * FROM %s LIMIT %d", qualifiedName, Math.min(limit, 100));

        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            int columnCount = rs.getMetaData().getColumnCount();
            List<String> columnNames = new ArrayList<>();
            for (int i = 1; i <= columnCount; i++) {
                columnNames.add(rs.getMetaData().getColumnName(i));
            }

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    Object value = rs.getObject(i);
                    row.put(columnNames.get(i - 1), formatValue(value));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            throw new IntrospectionException("Failed to sample rows: " + e.getMessage(), e);
        }

        return rows;
    }

    private Set<String> getPrimaryKeyColumns(
            final DatabaseMetaData metaData,
            final String catalog,
            final String schema,
            final String table) throws SQLException {

        Set<String> pkColumns = new HashSet<>();
        try (ResultSet rs = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                pkColumns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return pkColumns;
    }

    private String quoteIdentifier(final String identifier) {
        // Basic identifier quoting - prevents SQL injection
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private Object formatValue(final Object value) {
        if (value == null) {
            return null;
        }
        // Convert complex types to strings for JSON serialization
        if (value instanceof byte[]) {
            return "[binary data]";
        }
        if (value instanceof java.sql.Clob || value instanceof java.sql.Blob) {
            return "[LOB data]";
        }
        return value;
    }

    /**
     * Exception thrown when schema introspection fails.
     */
    public static class IntrospectionException extends RuntimeException {

        public IntrospectionException(final String message, final Throwable cause) {
            super(message, cause);
        }

    }

}
