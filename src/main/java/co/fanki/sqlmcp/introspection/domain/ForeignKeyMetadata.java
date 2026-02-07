package co.fanki.sqlmcp.introspection.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value object representing a foreign key relationship.
 *
 * <p>Captures the relationship between a source table's columns
 * and a referenced (target) table's columns.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class ForeignKeyMetadata {

    private final String name;
    private final String sourceSchema;
    private final String sourceTable;
    private final List<String> sourceColumns;
    private final String targetSchema;
    private final String targetTable;
    private final List<String> targetColumns;
    private final ReferentialAction onUpdate;
    private final ReferentialAction onDelete;

    private ForeignKeyMetadata(
            final String name,
            final String sourceSchema,
            final String sourceTable,
            final List<String> sourceColumns,
            final String targetSchema,
            final String targetTable,
            final List<String> targetColumns,
            final ReferentialAction onUpdate,
            final ReferentialAction onDelete) {
        this.name = name;
        this.sourceSchema = sourceSchema;
        this.sourceTable = Objects.requireNonNull(sourceTable);
        this.sourceColumns = Collections.unmodifiableList(new ArrayList<>(sourceColumns));
        this.targetSchema = targetSchema;
        this.targetTable = Objects.requireNonNull(targetTable);
        this.targetColumns = Collections.unmodifiableList(new ArrayList<>(targetColumns));
        this.onUpdate = onUpdate != null ? onUpdate : ReferentialAction.NO_ACTION;
        this.onDelete = onDelete != null ? onDelete : ReferentialAction.NO_ACTION;
    }

    /**
     * Creates a new ForeignKeyMetadata builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the foreign key constraint name.
     *
     * @return the constraint name, may be null
     */
    public String name() {
        return name;
    }

    /**
     * Returns the source table's schema.
     *
     * @return the source schema, may be null
     */
    public String sourceSchema() {
        return sourceSchema;
    }

    /**
     * Returns the source table name.
     *
     * @return the source table
     */
    public String sourceTable() {
        return sourceTable;
    }

    /**
     * Returns the source column names.
     *
     * @return unmodifiable list of source columns
     */
    public List<String> sourceColumns() {
        return sourceColumns;
    }

    /**
     * Returns the target (referenced) table's schema.
     *
     * @return the target schema, may be null
     */
    public String targetSchema() {
        return targetSchema;
    }

    /**
     * Returns the target (referenced) table name.
     *
     * @return the target table
     */
    public String targetTable() {
        return targetTable;
    }

    /**
     * Returns the target (referenced) column names.
     *
     * @return unmodifiable list of target columns
     */
    public List<String> targetColumns() {
        return targetColumns;
    }

    /**
     * Returns the ON UPDATE action.
     *
     * @return the referential action on update
     */
    public ReferentialAction onUpdate() {
        return onUpdate;
    }

    /**
     * Returns the ON DELETE action.
     *
     * @return the referential action on delete
     */
    public ReferentialAction onDelete() {
        return onDelete;
    }

    /**
     * Returns the qualified source table name.
     *
     * @return schema.table or just table
     */
    public String qualifiedSourceTable() {
        if (sourceSchema != null && !sourceSchema.isBlank()) {
            return sourceSchema + "." + sourceTable;
        }
        return sourceTable;
    }

    /**
     * Returns the qualified target table name.
     *
     * @return schema.table or just table
     */
    public String qualifiedTargetTable() {
        if (targetSchema != null && !targetSchema.isBlank()) {
            return targetSchema + "." + targetTable;
        }
        return targetTable;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ForeignKeyMetadata that = (ForeignKeyMetadata) o;
        return Objects.equals(name, that.name)
                && Objects.equals(sourceTable, that.sourceTable)
                && Objects.equals(targetTable, that.targetTable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, sourceTable, targetTable);
    }

    @Override
    public String toString() {
        return String.format("FK[%s: %s(%s) -> %s(%s)]",
                name != null ? name : "unnamed",
                qualifiedSourceTable(), String.join(", ", sourceColumns),
                qualifiedTargetTable(), String.join(", ", targetColumns));
    }

    /**
     * Referential actions for foreign key constraints.
     */
    public enum ReferentialAction {
        CASCADE,
        SET_NULL,
        SET_DEFAULT,
        RESTRICT,
        NO_ACTION;

        /**
         * Parses a JDBC referential action code.
         *
         * @param code the JDBC action code
         * @return the corresponding action
         */
        public static ReferentialAction fromJdbcCode(final int code) {
            return switch (code) {
                case 0 -> CASCADE;
                case 1 -> RESTRICT;
                case 2 -> SET_NULL;
                case 3 -> NO_ACTION;
                case 4 -> SET_DEFAULT;
                default -> NO_ACTION;
            };
        }
    }

    /**
     * Builder for ForeignKeyMetadata.
     */
    public static final class Builder {

        private String name;
        private String sourceSchema;
        private String sourceTable;
        private final List<String> sourceColumns = new ArrayList<>();
        private String targetSchema;
        private String targetTable;
        private final List<String> targetColumns = new ArrayList<>();
        private ReferentialAction onUpdate;
        private ReferentialAction onDelete;

        private Builder() {
        }

        public Builder name(final String name) {
            this.name = name;
            return this;
        }

        public Builder sourceSchema(final String sourceSchema) {
            this.sourceSchema = sourceSchema;
            return this;
        }

        public Builder sourceTable(final String sourceTable) {
            this.sourceTable = sourceTable;
            return this;
        }

        public Builder addSourceColumn(final String column) {
            this.sourceColumns.add(column);
            return this;
        }

        public Builder targetSchema(final String targetSchema) {
            this.targetSchema = targetSchema;
            return this;
        }

        public Builder targetTable(final String targetTable) {
            this.targetTable = targetTable;
            return this;
        }

        public Builder addTargetColumn(final String column) {
            this.targetColumns.add(column);
            return this;
        }

        public Builder onUpdate(final ReferentialAction onUpdate) {
            this.onUpdate = onUpdate;
            return this;
        }

        public Builder onDelete(final ReferentialAction onDelete) {
            this.onDelete = onDelete;
            return this;
        }

        /**
         * Builds the ForeignKeyMetadata instance.
         *
         * @return a new ForeignKeyMetadata
         */
        public ForeignKeyMetadata build() {
            return new ForeignKeyMetadata(
                    name, sourceSchema, sourceTable, sourceColumns,
                    targetSchema, targetTable, targetColumns,
                    onUpdate, onDelete
            );
        }

    }

}
