package co.fanki.sqlmcp.connection.domain;

/**
 * Supported database types for the SQL MCP server.
 *
 * <p>Each database type defines its JDBC driver class and URL pattern
 * for establishing connections.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public enum DatabaseType {

    /**
     * PostgreSQL database.
     */
    POSTGRESQL("org.postgresql.Driver", "jdbc:postgresql://%s:%d/%s"),

    /**
     * MySQL database.
     */
    MYSQL("com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s:%d/%s"),

    /**
     * MariaDB database.
     */
    MARIADB("org.mariadb.jdbc.Driver", "jdbc:mariadb://%s:%d/%s"),

    /**
     * SQLite database (file-based).
     */
    SQLITE("org.sqlite.JDBC", "jdbc:sqlite:%s");

    private final String driverClassName;
    private final String urlPattern;

    DatabaseType(final String driverClassName, final String urlPattern) {
        this.driverClassName = driverClassName;
        this.urlPattern = urlPattern;
    }

    /**
     * Returns the JDBC driver class name for this database type.
     *
     * @return the fully qualified driver class name
     */
    public String driverClassName() {
        return driverClassName;
    }

    /**
     * Builds a JDBC URL for the given connection parameters.
     *
     * @param host the database host
     * @param port the database port
     * @param database the database name
     * @return the formatted JDBC URL
     */
    public String buildUrl(final String host, final int port, final String database) {
        if (this == SQLITE) {
            return String.format(urlPattern, database);
        }
        return String.format(urlPattern, host, port, database);
    }

    /**
     * Returns the default port for this database type.
     *
     * @return the default port number
     */
    public int defaultPort() {
        return switch (this) {
            case POSTGRESQL -> 5432;
            case MYSQL, MARIADB -> 3306;
            case SQLITE -> 0;
        };
    }

}
