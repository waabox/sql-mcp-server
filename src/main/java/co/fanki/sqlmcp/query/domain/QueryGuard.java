package co.fanki.sqlmcp.query.domain;

import co.fanki.sqlmcp.connection.domain.DatabaseType;
import co.fanki.sqlmcp.query.domain.SqlTokenizer.SqlSyntaxException;
import co.fanki.sqlmcp.query.domain.SqlTokenizer.Token;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Domain service for validating SQL queries before they reach the database.
 *
 * <p>The guard is a defense-in-depth layer on top of the read-only database
 * session (see {@code DataSourceFactory}). It works on a dialect-aware token
 * stream, so string literals, quoted identifiers and comments are never
 * confused with SQL keywords. It enforces:
 * <ul>
 *   <li>A single statement starting with {@code SELECT} or {@code WITH}.</li>
 *   <li>No data-modifying keywords anywhere (including writable CTEs and
 *       {@code SELECT ... INTO}).</li>
 *   <li>No row-locking clauses ({@code FOR UPDATE}, {@code FOR SHARE},
 *       {@code LOCK IN SHARE MODE}).</li>
 *   <li>No functions that affect other sessions, the server, the file system
 *       or session settings (e.g. {@code pg_terminate_backend},
 *       {@code set_config}, {@code pg_sleep}, advisory locks).</li>
 *   <li>Table allow/deny lists, applied to every table referenced in
 *       {@code FROM} and {@code JOIN} clauses at any nesting level.</li>
 * </ul>
 *
 * <p>The query text itself is never rewritten; the database receives exactly
 * what was validated.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp.tables")
public class QueryGuard {

    /**
     * Keywords that are never valid inside a read-only query.
     */
    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "INTO",
            "DROP", "CREATE", "ALTER", "TRUNCATE", "GRANT", "REVOKE",
            "COPY", "CALL", "EXECUTE", "EXEC", "VACUUM", "REINDEX", "CLUSTER"
    );

    /**
     * Functions that act on other sessions, the server, files, sequences or
     * session settings.
     */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            // PostgreSQL: other sessions and server control
            "pg_terminate_backend", "pg_cancel_backend", "pg_reload_conf",
            "pg_rotate_logfile", "pg_switch_wal", "pg_promote",
            "pg_log_backend_memory_contexts", "pg_notify",
            // PostgreSQL: session settings (could disable our timeouts)
            "set_config",
            // PostgreSQL: sequences
            "nextval", "setval",
            // PostgreSQL: arbitrary SQL execution from a string
            "query_to_xml", "query_to_xml_and_xmlschema", "query_to_xmlschema",
            "cursor_to_xml", "cursor_to_xmlschema",
            // MySQL / MariaDB
            "sleep", "benchmark", "get_lock", "release_lock", "release_all_locks",
            "load_file", "sys_exec", "sys_eval",
            // SQLite
            "load_extension", "writefile", "readfile"
    );

    /**
     * Prefixes of forbidden function families.
     */
    private static final List<String> FORBIDDEN_FUNCTION_PREFIXES = List.of(
            "pg_sleep", "pg_advisory_", "pg_try_advisory_", "pg_stat_reset",
            "pg_stat_statements_reset", "pg_read_", "pg_ls_", "pg_stat_file",
            "pg_file_", "pg_create_", "pg_drop_", "pg_replication_", "pg_logical_",
            "pg_backup_", "pg_start_backup", "pg_stop_backup", "lo_", "dblink"
    );

    /**
     * Keywords that end a FROM list at the current nesting level.
     */
    private static final Set<String> FROM_LIST_TERMINATORS = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH",
            "WINDOW", "UNION", "INTERSECT", "EXCEPT", "FOR", "LOCK", "SELECT"
    );

    private List<String> allowList = new ArrayList<>();
    private List<String> denyList = new ArrayList<>();

    /**
     * Sets the table allow list from configuration.
     *
     * @param allowList list of allowed table patterns; {@code *} is a wildcard
     */
    public void setAllowList(final List<String> allowList) {
        this.allowList = allowList != null ? allowList : new ArrayList<>();
    }

    /**
     * Sets the table deny list from configuration.
     *
     * @param denyList list of denied table patterns; {@code *} is a wildcard
     */
    public void setDenyList(final List<String> denyList) {
        this.denyList = denyList != null ? denyList : new ArrayList<>();
    }

    /**
     * Validates a SQL query for safe, read-only execution.
     *
     * <p>Business rules: exactly one statement (trailing semicolons are allowed),
     * it must start with {@code SELECT} or {@code WITH}, and it must not contain
     * data-modifying keywords, locking clauses, forbidden functions, or
     * references to tables blocked by the allow/deny lists.
     *
     * @param query the SQL query to validate
     * @param dialect the database dialect used to tokenize the query
     * @return a ValidationResult indicating success or the reason for rejection
     */
    public ValidationResult validate(final String query, final DatabaseType dialect) {
        if (query == null || query.isBlank()) {
            return ValidationResult.failure("Query cannot be empty");
        }

        List<Token> tokens;
        try {
            tokens = stripTrailingSemicolons(SqlTokenizer.tokenize(query, dialect));
        } catch (SqlSyntaxException e) {
            return ValidationResult.failure(e.getMessage());
        }

        if (tokens.isEmpty()) {
            return ValidationResult.failure("Query cannot be empty");
        }

        for (Token token : tokens) {
            if (token.isSymbol(";")) {
                return ValidationResult.failure("Multiple statements are not allowed");
            }
        }

        Token first = firstWord(tokens);
        if (first == null || !(first.isKeyword("SELECT") || first.isKeyword("WITH"))) {
            return ValidationResult.failure(
                    "Only SELECT statements are allowed. Query appears to be: "
                    + (first != null ? first.text().toUpperCase(Locale.ROOT) : "UNKNOWN"));
        }

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);

            if (token.type() == SqlTokenizer.TokenType.WORD) {
                String upper = token.text().toUpperCase(Locale.ROOT);
                if (FORBIDDEN_KEYWORDS.contains(upper)) {
                    return ValidationResult.failure("Query contains prohibited keyword: " + upper
                            + " (quote the identifier if it is a column name)");
                }
                if (isLockingClause(tokens, i)) {
                    return ValidationResult.failure("Row-locking clauses are not allowed");
                }
            }

            if (token.isIdentifier() && next(tokens, i).isSymbol("(")
                    && isForbiddenFunction(token.normalized())) {
                return ValidationResult.failure("Function not allowed: " + token.normalized());
            }
        }

        for (String table : referencedTables(tokens)) {
            if (!isTableAllowed(table)) {
                return ValidationResult.failure("Access to table '" + table + "' is not allowed");
            }
        }

        return ValidationResult.success();
    }

    /**
     * Checks whether a table may be accessed according to the allow/deny lists.
     *
     * <p>Business rules: the deny list wins over the allow list; an empty allow
     * list allows every table that is not denied. A pattern without a dot is
     * matched against the bare table name, a pattern with a dot against the
     * schema-qualified name.
     *
     * @param schema the schema name, may be null
     * @param table the table name, never null
     * @return true if access is allowed
     */
    public boolean isTableAllowed(final String schema, final String table) {
        String qualified = schema != null && !schema.isBlank() ? schema + "." + table : table;
        return isTableAllowed(qualified);
    }

    private boolean isTableAllowed(final String qualifiedName) {
        String lower = qualifiedName.toLowerCase(Locale.ROOT);
        String bare = lower.substring(lower.lastIndexOf('.') + 1);

        for (String pattern : denyList) {
            if (matches(lower, bare, pattern)) {
                return false;
            }
        }
        if (allowList.isEmpty()) {
            return true;
        }
        for (String pattern : allowList) {
            if (matches(lower, bare, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(final String qualified, final String bare, final String pattern) {
        String lowerPattern = pattern.toLowerCase(Locale.ROOT);
        String regex = Pattern.quote(lowerPattern).replace("*", "\\E.*\\Q");
        String candidate = lowerPattern.contains(".") ? qualified : bare;
        return Pattern.matches(regex, candidate);
    }

    private static List<Token> stripTrailingSemicolons(final List<Token> tokens) {
        int end = tokens.size();
        while (end > 0 && tokens.get(end - 1).isSymbol(";")) {
            end--;
        }
        return tokens.subList(0, end);
    }

    private static Token firstWord(final List<Token> tokens) {
        for (Token token : tokens) {
            if (token.isSymbol("(")) {
                continue;
            }
            return token.type() == SqlTokenizer.TokenType.WORD ? token : null;
        }
        return null;
    }

    private static Token next(final List<Token> tokens, final int index) {
        return index + 1 < tokens.size()
                ? tokens.get(index + 1)
                : new Token(SqlTokenizer.TokenType.SYMBOL, "");
    }

    private static boolean isLockingClause(final List<Token> tokens, final int index) {
        Token token = tokens.get(index);
        Token following = next(tokens, index);
        if (token.isKeyword("FOR")) {
            // FOR UPDATE is caught by the UPDATE keyword; FOR [NO] KEY ... and FOR SHARE here.
            return following.isKeyword("SHARE") || following.isKeyword("KEY") || following.isKeyword("NO");
        }
        return token.isKeyword("LOCK") && following.isKeyword("IN") && next(tokens, index + 1).isKeyword("SHARE");
    }

    private static boolean isForbiddenFunction(final String name) {
        if (FORBIDDEN_FUNCTIONS.contains(name)) {
            return true;
        }
        for (String prefix : FORBIDDEN_FUNCTION_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects every table referenced in a FROM or JOIN clause, at any depth.
     *
     * <p>Each parenthesis level is a frame. FROM only introduces tables in a
     * frame that contains a SELECT (so {@code extract(year FROM ts)} is ignored).
     * Unqualified names that match a CTE are skipped because the CTE shadows them.
     */
    private static List<String> referencedTables(final List<Token> tokens) {
        List<String> tables = new ArrayList<>();
        Set<String> cteNames = new HashSet<>();
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame());

        int i = 0;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            Frame frame = frames.peek();

            if (token.isSymbol("(")) {
                frame.expectTable = false;
                frame.expectCteName = false;
                frames.push(new Frame());
                i++;
                continue;
            }
            if (token.isSymbol(")")) {
                if (frames.size() > 1) {
                    frames.pop();
                }
                i++;
                continue;
            }
            if (token.isSymbol(",")) {
                if (frame.inWith) {
                    frame.expectCteName = true;
                } else if (frame.inFromList) {
                    frame.expectTable = true;
                }
                i++;
                continue;
            }

            if (token.isKeyword("WITH")) {
                frame.inWith = true;
                frame.expectCteName = true;
                i++;
                continue;
            }
            if (frame.expectCteName && token.isIdentifier() && !token.isKeyword("RECURSIVE")) {
                cteNames.add(token.normalized());
                frame.expectCteName = false;
                i++;
                continue;
            }
            if (token.isKeyword("SELECT")) {
                frame.hasSelect = true;
                frame.inWith = false;
                frame.inFromList = false;
                i++;
                continue;
            }
            if (token.isKeyword("FROM") && frame.hasSelect && !previous(tokens, i).isKeyword("DISTINCT")) {
                frame.inFromList = true;
                frame.expectTable = true;
                i++;
                continue;
            }
            if (token.isKeyword("JOIN")) {
                frame.inFromList = true;
                frame.expectTable = true;
                i++;
                continue;
            }
            if (token.type() == SqlTokenizer.TokenType.WORD
                    && FROM_LIST_TERMINATORS.contains(token.text().toUpperCase(Locale.ROOT))) {
                frame.inFromList = false;
                frame.expectTable = false;
                i++;
                continue;
            }

            if (frame.expectTable && token.isIdentifier()) {
                if (token.isKeyword("LATERAL") || token.isKeyword("ONLY")) {
                    i++;
                    continue;
                }
                StringBuilder name = new StringBuilder(token.normalized());
                int j = i + 1;
                while (j + 1 < tokens.size() && tokens.get(j).isSymbol(".") && tokens.get(j + 1).isIdentifier()) {
                    name.append('.').append(tokens.get(j + 1).normalized());
                    j += 2;
                }
                frame.expectTable = false;
                boolean isFunction = j < tokens.size() && tokens.get(j).isSymbol("(");
                String qualified = name.toString();
                if (!isFunction && !(qualified.indexOf('.') < 0 && cteNames.contains(qualified))) {
                    tables.add(qualified);
                }
                i = j;
                continue;
            }

            i++;
        }
        return tables;
    }

    private static Token previous(final List<Token> tokens, final int index) {
        return index > 0 ? tokens.get(index - 1) : new Token(SqlTokenizer.TokenType.SYMBOL, "");
    }

    /**
     * Parsing state for one parenthesis level.
     */
    private static final class Frame {
        private boolean hasSelect;
        private boolean inWith;
        private boolean expectCteName;
        private boolean inFromList;
        private boolean expectTable;
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
