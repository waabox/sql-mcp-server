package co.fanki.sqlmcp.query.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Domain service for validating and sanitizing SQL queries.
 *
 * <p>Enforces security policies including:
 * <ul>
 *   <li>READ-only enforcement (only SELECT statements)</li>
 *   <li>Table allow/deny lists</li>
 *   <li>Detection of dangerous patterns (comments, injections)</li>
 * </ul>
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp.tables")
public class QueryGuard {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SQL_COMMENT_PATTERN = Pattern.compile(
            "/\\*.*?\\*/|--.*$", Pattern.MULTILINE | Pattern.DOTALL);

    /**
     * Dangerous SQL keywords that indicate non-SELECT operations.
     */
    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "CREATE", "ALTER",
            "TRUNCATE", "GRANT", "REVOKE", "EXECUTE", "EXEC",
            "INTO OUTFILE", "INTO DUMPFILE", "LOAD_FILE"
    );

    /**
     * Patterns that might indicate SQL injection attempts.
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("'\\s*OR\\s+'", Pattern.CASE_INSENSITIVE),
            Pattern.compile("'\\s*OR\\s+1\\s*=\\s*1", Pattern.CASE_INSENSITIVE),
            Pattern.compile(";\\s*(DROP|DELETE|UPDATE|INSERT)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("UNION\\s+ALL\\s+SELECT", Pattern.CASE_INSENSITIVE)
    );

    private List<String> allowList = new ArrayList<>();
    private List<String> denyList = new ArrayList<>();

    /**
     * Sets the table allow list from configuration.
     *
     * @param allowList list of allowed table patterns
     */
    public void setAllowList(final List<String> allowList) {
        this.allowList = allowList != null ? allowList : new ArrayList<>();
    }

    /**
     * Sets the table deny list from configuration.
     *
     * @param denyList list of denied table patterns
     */
    public void setDenyList(final List<String> denyList) {
        this.denyList = denyList != null ? denyList : new ArrayList<>();
    }

    /**
     * Validates a SQL query for safety.
     *
     * @param query the SQL query to validate
     * @return a ValidationResult indicating success or failure
     */
    public ValidationResult validate(final String query) {
        if (query == null || query.isBlank()) {
            return ValidationResult.failure("Query cannot be empty");
        }

        // Remove comments for analysis
        String cleanQuery = removeComments(query);
        String normalizedQuery = normalizeWhitespace(cleanQuery);
        String upperQuery = normalizedQuery.toUpperCase(Locale.ROOT);

        // Check if it's a SELECT statement
        if (!isSelectStatement(upperQuery)) {
            return ValidationResult.failure(
                    "Only SELECT statements are allowed. Query appears to be: " +
                    detectStatementType(upperQuery));
        }

        // Check for dangerous keywords embedded in the query
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (containsKeyword(upperQuery, keyword)) {
                return ValidationResult.failure(
                        "Query contains prohibited keyword: " + keyword);
            }
        }

        // Check for injection patterns
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(normalizedQuery).find()) {
                return ValidationResult.failure(
                        "Query contains suspicious pattern that may indicate SQL injection");
            }
        }

        // Check table access
        List<String> tables = extractTableNames(normalizedQuery);
        for (String table : tables) {
            if (!isTableAllowed(table)) {
                return ValidationResult.failure(
                        "Access to table '" + table + "' is not allowed");
            }
        }

        return ValidationResult.success();
    }

    /**
     * Sanitizes a query by removing comments and normalizing whitespace.
     *
     * @param query the query to sanitize
     * @return the sanitized query
     */
    public String sanitize(final String query) {
        String noComments = removeComments(query);
        return normalizeWhitespace(noComments);
    }

    private String removeComments(final String query) {
        return SQL_COMMENT_PATTERN.matcher(query).replaceAll(" ");
    }

    private String normalizeWhitespace(final String query) {
        return WHITESPACE_PATTERN.matcher(query.trim()).replaceAll(" ");
    }

    private boolean isSelectStatement(final String upperQuery) {
        // Must start with SELECT, WITH (for CTEs), or (SELECT for subqueries
        return upperQuery.startsWith("SELECT ")
                || upperQuery.startsWith("WITH ")
                || upperQuery.startsWith("(SELECT ");
    }

    private String detectStatementType(final String upperQuery) {
        String[] statements = {"INSERT", "UPDATE", "DELETE", "CREATE", "DROP",
                "ALTER", "TRUNCATE", "GRANT", "REVOKE"};
        for (String stmt : statements) {
            if (upperQuery.startsWith(stmt + " ") || upperQuery.startsWith(stmt + "(")) {
                return stmt;
            }
        }
        return "UNKNOWN";
    }

    private boolean containsKeyword(final String upperQuery, final String keyword) {
        // Check for keyword as a standalone word (not part of column/table name)
        String pattern = "\\b" + keyword.replace(" ", "\\s+") + "\\b";
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(upperQuery).find();
    }

    private List<String> extractTableNames(final String query) {
        // Simplified table extraction - looks for FROM and JOIN clauses
        List<String> tables = new ArrayList<>();
        String upperQuery = query.toUpperCase(Locale.ROOT);

        // Extract tables after FROM
        int fromIndex = upperQuery.indexOf(" FROM ");
        if (fromIndex > 0) {
            extractTablesFromClause(query, fromIndex + 6, tables);
        }

        // Extract tables after JOIN
        int joinIndex = 0;
        while ((joinIndex = upperQuery.indexOf(" JOIN ", joinIndex)) > 0) {
            extractTablesFromClause(query, joinIndex + 6, tables);
            joinIndex += 6;
        }

        return tables;
    }

    private void extractTablesFromClause(
            final String query,
            final int startIndex,
            final List<String> tables) {

        int endIndex = query.length();
        String upperQuery = query.toUpperCase(Locale.ROOT);

        // Find end of table reference (WHERE, JOIN, ON, GROUP, ORDER, LIMIT, etc.)
        String[] terminators = {" WHERE ", " JOIN ", " LEFT ", " RIGHT ", " INNER ",
                " OUTER ", " ON ", " GROUP ", " ORDER ", " LIMIT ", " HAVING ", ")"};

        for (String term : terminators) {
            int idx = upperQuery.indexOf(term, startIndex);
            if (idx > 0 && idx < endIndex) {
                endIndex = idx;
            }
        }

        String tableClause = query.substring(startIndex, endIndex).trim();

        // Split by comma for multiple tables
        String[] tableRefs = tableClause.split(",");
        for (String tableRef : tableRefs) {
            String table = tableRef.trim().split("\\s+")[0]; // First word is table name
            table = table.replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
            if (!table.isEmpty() && !table.equals("(")) {
                tables.add(table.toLowerCase(Locale.ROOT));
            }
        }
    }

    private boolean isTableAllowed(final String tableName) {
        String lowerTable = tableName.toLowerCase(Locale.ROOT);

        // Check deny list first
        for (String pattern : denyList) {
            if (matchesPattern(lowerTable, pattern.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }

        // If allow list is empty, all tables (not in deny list) are allowed
        if (allowList.isEmpty()) {
            return true;
        }

        // Check allow list
        for (String pattern : allowList) {
            if (matchesPattern(lowerTable, pattern.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesPattern(final String tableName, final String pattern) {
        // Support simple wildcards: * matches anything
        String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*");
        return Pattern.matches(regex, tableName);
    }

    /**
     * Result of query validation.
     */
    public static final class ValidationResult {

        private final boolean valid;
        private final String error;

        private ValidationResult(final boolean valid, final String error) {
            this.valid = valid;
            this.error = error;
        }

        /**
         * Creates a successful validation result.
         *
         * @return success result
         */
        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        /**
         * Creates a failed validation result.
         *
         * @param error the error message
         * @return failure result
         */
        public static ValidationResult failure(final String error) {
            return new ValidationResult(false, error);
        }

        /**
         * Returns whether the query is valid.
         *
         * @return true if valid
         */
        public boolean isValid() {
            return valid;
        }

        /**
         * Returns the error message if validation failed.
         *
         * @return the error message, or null if valid
         */
        public String error() {
            return error;
        }

    }

}
