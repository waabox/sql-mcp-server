package co.fanki.sqlmcp.query.domain;

import co.fanki.sqlmcp.connection.domain.DatabaseType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Dialect-aware lexer that splits a SQL statement into tokens.
 *
 * <p>The tokenizer understands the quoting and comment rules of each supported
 * engine so that the {@link QueryGuard} never mistakes the content of a string
 * literal or a comment for executable SQL, and never mistakes executable SQL
 * for a literal or a comment:
 * <ul>
 *   <li>PostgreSQL: {@code --} comments, nested block comments, standard strings
 *       (no backslash escapes), {@code E'...'} escape strings, dollar-quoted
 *       strings and double-quoted identifiers.</li>
 *   <li>MySQL/MariaDB: {@code #} comments, {@code --} comments only when followed
 *       by whitespace, non-nested block comments, backslash escapes, double-quoted
 *       strings and backtick identifiers. Executable comments ({@code /*!})
 *       are rejected.</li>
 *   <li>SQLite: {@code --} comments, non-nested block comments, double-quoted,
 *       backtick and bracket identifiers.</li>
 * </ul>
 *
 * <p>Comments are dropped from the output.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
final class SqlTokenizer {

    private final String sql;
    private final DatabaseType dialect;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;

    private SqlTokenizer(final String sql, final DatabaseType dialect) {
        this.sql = Objects.requireNonNull(sql, "sql must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
    }

    /**
     * Tokenizes a SQL statement.
     *
     * @param sql the SQL text, never null
     * @param dialect the database dialect whose lexical rules apply, never null
     * @return the tokens in order of appearance, excluding comments
     * @throws SqlSyntaxException if a literal, identifier or comment is not
     *         terminated, or the text uses a construct that cannot be analyzed
     */
    static List<Token> tokenize(final String sql, final DatabaseType dialect) {
        SqlTokenizer tokenizer = new SqlTokenizer(sql, dialect);
        tokenizer.run();
        return List.copyOf(tokenizer.tokens);
    }

    private boolean isMySql() {
        return dialect == DatabaseType.MYSQL || dialect == DatabaseType.MARIADB;
    }

    private void run() {
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && peek(1) == '-' && isLineCommentStart()) {
                skipLineComment();
            } else if (c == '#' && isMySql()) {
                skipLineComment();
            } else if (c == '/' && peek(1) == '*') {
                skipBlockComment();
            } else if (c == '\'') {
                readString('\'', isMySql());
            } else if (c == '"') {
                if (isMySql()) {
                    readString('"', true);
                } else {
                    readQuotedIdentifier('"', '"');
                }
            } else if (c == '`' && dialect != DatabaseType.POSTGRESQL) {
                readQuotedIdentifier('`', '`');
            } else if (c == '[' && dialect == DatabaseType.SQLITE) {
                readQuotedIdentifier('[', ']');
            } else if (c == '$' && dialect == DatabaseType.POSTGRESQL && tryReadDollarQuoted()) {
                continue;
            } else if (isWordStart(c)) {
                readWord();
            } else if (Character.isDigit(c)) {
                readNumber();
            } else {
                tokens.add(new Token(TokenType.SYMBOL, String.valueOf(c)));
                pos++;
            }
        }
    }

    private char peek(final int offset) {
        int index = pos + offset;
        return index < sql.length() ? sql.charAt(index) : '\0';
    }

    private boolean isLineCommentStart() {
        if (!isMySql()) {
            return true;
        }
        // MySQL requires whitespace (or end of input) after "--".
        char next = peek(2);
        return next == '\0' || Character.isWhitespace(next) || Character.isISOControl(next);
    }

    private void skipLineComment() {
        while (pos < sql.length() && sql.charAt(pos) != '\n') {
            pos++;
        }
    }

    private void skipBlockComment() {
        if (isMySql() && peek(2) == '!') {
            throw new SqlSyntaxException("MySQL executable comments (/*! ... */) are not allowed");
        }
        boolean nested = dialect == DatabaseType.POSTGRESQL;
        int depth = 0;
        while (pos < sql.length()) {
            if (sql.charAt(pos) == '/' && peek(1) == '*' && (nested || depth == 0)) {
                depth++;
                pos += 2;
            } else if (sql.charAt(pos) == '*' && peek(1) == '/') {
                depth--;
                pos += 2;
                if (depth == 0) {
                    return;
                }
            } else {
                pos++;
            }
        }
        throw new SqlSyntaxException("Unterminated block comment");
    }

    private void readString(final char quote, final boolean backslashEscapes) {
        pos++;
        StringBuilder value = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (backslashEscapes && c == '\\' && pos + 1 < sql.length()) {
                value.append(sql.charAt(pos + 1));
                pos += 2;
            } else if (c == quote && peek(1) == quote) {
                value.append(quote);
                pos += 2;
            } else if (c == quote) {
                pos++;
                tokens.add(new Token(TokenType.STRING, value.toString()));
                return;
            } else {
                value.append(c);
                pos++;
            }
        }
        throw new SqlSyntaxException("Unterminated string literal");
    }

    private void readQuotedIdentifier(final char open, final char close) {
        pos++;
        StringBuilder value = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (c == close && open == close && peek(1) == close) {
                value.append(close);
                pos += 2;
            } else if (c == close) {
                pos++;
                tokens.add(new Token(TokenType.QUOTED_IDENTIFIER, value.toString()));
                return;
            } else {
                value.append(c);
                pos++;
            }
        }
        throw new SqlSyntaxException("Unterminated quoted identifier");
    }

    private boolean tryReadDollarQuoted() {
        int end = pos + 1;
        while (end < sql.length() && isWordPart(sql.charAt(end)) && sql.charAt(end) != '$') {
            end++;
        }
        if (end >= sql.length() || sql.charAt(end) != '$') {
            return false;
        }
        String tag = sql.substring(pos, end + 1);
        if (tag.length() > 2 && Character.isDigit(tag.charAt(1))) {
            // "$1" style positional parameter, not a dollar quote.
            return false;
        }
        int close = sql.indexOf(tag, end + 1);
        if (close < 0) {
            throw new SqlSyntaxException("Unterminated dollar-quoted string");
        }
        tokens.add(new Token(TokenType.STRING, sql.substring(end + 1, close)));
        pos = close + tag.length();
        return true;
    }

    private void readWord() {
        int start = pos;
        while (pos < sql.length() && isWordPart(sql.charAt(pos))) {
            pos++;
        }
        String word = sql.substring(start, pos);
        // PostgreSQL E'...' escape strings honor backslash escapes.
        if (dialect == DatabaseType.POSTGRESQL && word.equalsIgnoreCase("E")
                && pos < sql.length() && sql.charAt(pos) == '\'') {
            readString('\'', true);
            return;
        }
        tokens.add(new Token(TokenType.WORD, word));
    }

    private void readNumber() {
        int start = pos;
        while (pos < sql.length() && (isWordPart(sql.charAt(pos)) || sql.charAt(pos) == '.')) {
            pos++;
        }
        tokens.add(new Token(TokenType.NUMBER, sql.substring(start, pos)));
    }

    private static boolean isWordStart(final char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isWordPart(final char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /**
     * Lexical category of a token.
     */
    enum TokenType {
        WORD,
        QUOTED_IDENTIFIER,
        STRING,
        NUMBER,
        SYMBOL
    }

    /**
     * A single lexical token.
     *
     * @param type the token category
     * @param text the token text; for strings and quoted identifiers the unquoted content
     */
    record Token(TokenType type, String text) {

        /**
         * Returns whether this token is an unquoted word equal to the given keyword.
         *
         * @param keyword the keyword in upper case
         * @return true if this token is that keyword
         */
        boolean isKeyword(final String keyword) {
            return type == TokenType.WORD && text.equalsIgnoreCase(keyword);
        }

        /**
         * Returns whether this token is the given symbol.
         *
         * @param symbol the symbol text
         * @return true if this token is that symbol
         */
        boolean isSymbol(final String symbol) {
            return type == TokenType.SYMBOL && text.equals(symbol);
        }

        /**
         * Returns whether this token can name a database object.
         *
         * @return true for unquoted words and quoted identifiers
         */
        boolean isIdentifier() {
            return type == TokenType.WORD || type == TokenType.QUOTED_IDENTIFIER;
        }

        /**
         * Returns the identifier normalized for comparison.
         *
         * @return the lower-cased text
         */
        String normalized() {
            return text.toLowerCase(Locale.ROOT);
        }

    }

    /**
     * Thrown when the SQL text cannot be tokenized safely.
     */
    static final class SqlSyntaxException extends RuntimeException {

        SqlSyntaxException(final String message) {
            super(message);
        }

    }

}
