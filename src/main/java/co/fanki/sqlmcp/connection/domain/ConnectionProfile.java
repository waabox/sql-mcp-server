package co.fanki.sqlmcp.connection.domain;

import java.util.Objects;

/**
 * Immutable value object representing a database connection profile.
 *
 * <p>Contains all necessary information to establish a connection to a SQL database.
 * Instances are created via the static factory method {@link #create}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class ConnectionProfile {

    private final String name;
    private final DatabaseType type;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String schema;
    private final boolean readOnly;

    private ConnectionProfile(
            final String name,
            final DatabaseType type,
            final String host,
            final int port,
            final String database,
            final String username,
            final String password,
            final String schema,
            final boolean readOnly) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.host = host;
        this.port = port;
        this.database = Objects.requireNonNull(database, "database cannot be null");
        this.username = username;
        this.password = password;
        this.schema = schema;
        this.readOnly = readOnly;
    }

    /**
     * Creates a new connection profile.
     *
     * @param name unique identifier for this profile
     * @param type the database type
     * @param host the database host (null for SQLite)
     * @param port the database port (0 for default)
     * @param database the database name or file path
     * @param username the database username (null for SQLite)
     * @param password the database password (null for SQLite)
     * @param schema the default schema (null for default)
     * @param readOnly whether to enforce read-only mode
     * @return a new ConnectionProfile instance
     */
    public static ConnectionProfile create(
            final String name,
            final DatabaseType type,
            final String host,
            final int port,
            final String database,
            final String username,
            final String password,
            final String schema,
            final boolean readOnly) {

        int effectivePort = port > 0 ? port : type.defaultPort();
        return new ConnectionProfile(
                name, type, host, effectivePort, database,
                username, password, schema, readOnly
        );
    }

    /**
     * Returns the unique name of this connection profile.
     *
     * @return the profile name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the database type.
     *
     * @return the database type
     */
    public DatabaseType type() {
        return type;
    }

    /**
     * Returns the database host.
     *
     * @return the host, may be null for SQLite
     */
    public String host() {
        return host;
    }

    /**
     * Returns the database port.
     *
     * @return the port number
     */
    public int port() {
        return port;
    }

    /**
     * Returns the database name or file path.
     *
     * @return the database name
     */
    public String database() {
        return database;
    }

    /**
     * Returns the database username.
     *
     * @return the username, may be null
     */
    public String username() {
        return username;
    }

    /**
     * Returns the database password.
     *
     * @return the password, may be null
     */
    public String password() {
        return password;
    }

    /**
     * Returns the default schema.
     *
     * @return the schema, may be null
     */
    public String schema() {
        return schema;
    }

    /**
     * Returns whether this connection should be read-only.
     *
     * @return true if read-only mode is enforced
     */
    public boolean readOnly() {
        return readOnly;
    }

    /**
     * Builds the JDBC URL for this connection profile.
     *
     * @return the JDBC connection URL
     */
    public String jdbcUrl() {
        return type.buildUrl(host, port, database);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ConnectionProfile that = (ConnectionProfile) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return String.format(
                "ConnectionProfile[name=%s, type=%s, host=%s, port=%d, database=%s, readOnly=%s]",
                name, type, host, port, database, readOnly
        );
    }

}
