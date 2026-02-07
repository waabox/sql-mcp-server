package co.fanki.sqlmcp.introspection.domain;

import java.util.Objects;

/**
 * Immutable value object representing database column metadata.
 *
 * <p>Contains detailed information about a column's definition and constraints.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class ColumnMetadata {

    private final String name;
    private final String dataType;
    private final int jdbcType;
    private final int size;
    private final int decimalDigits;
    private final boolean nullable;
    private final String defaultValue;
    private final boolean primaryKey;
    private final boolean autoIncrement;
    private final String comment;
    private final int ordinalPosition;

    private ColumnMetadata(
            final String name,
            final String dataType,
            final int jdbcType,
            final int size,
            final int decimalDigits,
            final boolean nullable,
            final String defaultValue,
            final boolean primaryKey,
            final boolean autoIncrement,
            final String comment,
            final int ordinalPosition) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.dataType = Objects.requireNonNull(dataType, "dataType cannot be null");
        this.jdbcType = jdbcType;
        this.size = size;
        this.decimalDigits = decimalDigits;
        this.nullable = nullable;
        this.defaultValue = defaultValue;
        this.primaryKey = primaryKey;
        this.autoIncrement = autoIncrement;
        this.comment = comment;
        this.ordinalPosition = ordinalPosition;
    }

    /**
     * Creates a new ColumnMetadata builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the column name.
     *
     * @return the column name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the SQL data type name.
     *
     * @return the data type (e.g., "VARCHAR", "INTEGER")
     */
    public String dataType() {
        return dataType;
    }

    /**
     * Returns the JDBC type code.
     *
     * @return the java.sql.Types constant
     */
    public int jdbcType() {
        return jdbcType;
    }

    /**
     * Returns the column size.
     *
     * <p>For character types, this is the maximum length.
     * For numeric types, this is the precision.
     *
     * @return the column size
     */
    public int size() {
        return size;
    }

    /**
     * Returns the number of decimal digits.
     *
     * @return the scale for numeric types
     */
    public int decimalDigits() {
        return decimalDigits;
    }

    /**
     * Returns whether the column allows NULL values.
     *
     * @return true if nullable
     */
    public boolean nullable() {
        return nullable;
    }

    /**
     * Returns the default value expression.
     *
     * @return the default value, may be null
     */
    public String defaultValue() {
        return defaultValue;
    }

    /**
     * Returns whether this column is part of the primary key.
     *
     * @return true if primary key
     */
    public boolean primaryKey() {
        return primaryKey;
    }

    /**
     * Returns whether this column auto-increments.
     *
     * @return true if auto-increment
     */
    public boolean autoIncrement() {
        return autoIncrement;
    }

    /**
     * Returns the column comment/description.
     *
     * @return the comment, may be null
     */
    public String comment() {
        return comment;
    }

    /**
     * Returns the ordinal position (1-based).
     *
     * @return the column position in the table
     */
    public int ordinalPosition() {
        return ordinalPosition;
    }

    /**
     * Returns a human-readable type description.
     *
     * <p>Includes size/precision where applicable.
     *
     * @return formatted type string (e.g., "VARCHAR(255)", "NUMERIC(10,2)")
     */
    public String typeDescription() {
        if (size > 0) {
            if (decimalDigits > 0) {
                return String.format("%s(%d,%d)", dataType, size, decimalDigits);
            }
            return String.format("%s(%d)", dataType, size);
        }
        return dataType;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ColumnMetadata that = (ColumnMetadata) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return String.format("ColumnMetadata[%s %s%s%s]",
                name, typeDescription(),
                nullable ? "" : " NOT NULL",
                primaryKey ? " PK" : "");
    }

    /**
     * Builder for ColumnMetadata.
     */
    public static final class Builder {

        private String name;
        private String dataType;
        private int jdbcType;
        private int size;
        private int decimalDigits;
        private boolean nullable = true;
        private String defaultValue;
        private boolean primaryKey;
        private boolean autoIncrement;
        private String comment;
        private int ordinalPosition;

        private Builder() {
        }

        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        public Builder dataType(final String dataType) {
            this.dataType = dataType;
            return this;
        }

        public Builder jdbcType(final int jdbcType) {
            this.jdbcType = jdbcType;
            return this;
        }

        public Builder size(final int size) {
            this.size = size;
            return this;
        }

        public Builder decimalDigits(final int decimalDigits) {
            this.decimalDigits = decimalDigits;
            return this;
        }

        public Builder nullable(final boolean nullable) {
            this.nullable = nullable;
            return this;
        }

        public Builder defaultValue(final String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        public Builder primaryKey(final boolean primaryKey) {
            this.primaryKey = primaryKey;
            return this;
        }

        public Builder autoIncrement(final boolean autoIncrement) {
            this.autoIncrement = autoIncrement;
            return this;
        }

        public Builder comment(final String comment) {
            this.comment = comment;
            return this;
        }

        public Builder ordinalPosition(final int ordinalPosition) {
            this.ordinalPosition = ordinalPosition;
            return this;
        }

        /**
         * Builds the ColumnMetadata instance.
         *
         * @return a new ColumnMetadata
         */
        public ColumnMetadata build() {
            return new ColumnMetadata(
                    name, dataType, jdbcType, size, decimalDigits,
                    nullable, defaultValue, primaryKey, autoIncrement,
                    comment, ordinalPosition
            );
        }

    }

}
