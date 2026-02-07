package co.fanki.sqlmcp.introspection.domain;

import java.util.Objects;

/**
 * Immutable value object representing database table metadata.
 *
 * <p>Contains essential information about a table's structure and identity.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class TableMetadata {

    private final String catalog;
    private final String schema;
    private final String name;
    private final TableType type;
    private final String comment;

    private TableMetadata(
            final String catalog,
            final String schema,
            final String name,
            final TableType type,
            final String comment) {
        this.catalog = catalog;
        this.schema = schema;
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.comment = comment;
    }

    /**
     * Creates a new TableMetadata instance.
     *
     * @param catalog the database catalog (may be null)
     * @param schema the database schema (may be null)
     * @param name the table name
     * @param type the table type
     * @param comment the table comment (may be null)
     * @return a new TableMetadata instance
     */
    public static TableMetadata create(
            final String catalog,
            final String schema,
            final String name,
            final TableType type,
            final String comment) {
        return new TableMetadata(catalog, schema, name, type, comment);
    }

    /**
     * Returns the database catalog.
     *
     * @return the catalog, may be null
     */
    public String catalog() {
        return catalog;
    }

    /**
     * Returns the database schema.
     *
     * @return the schema, may be null
     */
    public String schema() {
        return schema;
    }

    /**
     * Returns the table name.
     *
     * @return the table name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the table type.
     *
     * @return the table type
     */
    public TableType type() {
        return type;
    }

    /**
     * Returns the table comment/description.
     *
     * @return the comment, may be null
     */
    public String comment() {
        return comment;
    }

    /**
     * Returns the fully qualified table name.
     *
     * @return schema.name or just name if schema is null
     */
    public String qualifiedName() {
        if (schema != null && !schema.isBlank()) {
            return schema + "." + name;
        }
        return name;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TableMetadata that = (TableMetadata) o;
        return Objects.equals(catalog, that.catalog)
                && Objects.equals(schema, that.schema)
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalog, schema, name);
    }

    @Override
    public String toString() {
        return String.format("TableMetadata[%s, type=%s]", qualifiedName(), type);
    }

    /**
     * Types of database tables.
     */
    public enum TableType {
        TABLE,
        VIEW,
        MATERIALIZED_VIEW,
        SYSTEM_TABLE,
        FOREIGN_TABLE,
        OTHER;

        /**
         * Parses a JDBC table type string to TableType.
         *
         * @param jdbcType the JDBC type string
         * @return the corresponding TableType
         */
        public static TableType fromJdbcType(final String jdbcType) {
            if (jdbcType == null) {
                return OTHER;
            }
            return switch (jdbcType.toUpperCase()) {
                case "TABLE" -> TABLE;
                case "VIEW" -> VIEW;
                case "MATERIALIZED VIEW" -> MATERIALIZED_VIEW;
                case "SYSTEM TABLE" -> SYSTEM_TABLE;
                case "FOREIGN TABLE" -> FOREIGN_TABLE;
                default -> OTHER;
            };
        }
    }

}
