package co.fanki.sqlmcp.connection.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository for managing database connection profiles.
 *
 * <p>Loads connection profiles from application configuration and provides
 * lookup capabilities. Profiles are indexed by name for fast retrieval.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp")
public class ConnectionProfileRepository {

    private final Map<String, ConnectionProfile> profilesByName;
    private List<ConnectionProperties> connections;

    /**
     * Creates a new repository instance.
     */
    public ConnectionProfileRepository() {
        this.profilesByName = new ConcurrentHashMap<>();
        this.connections = new ArrayList<>();
    }

    /**
     * Sets the connection configurations from application properties.
     *
     * <p>This method is called by Spring during configuration binding.
     *
     * @param connections the list of connection property objects
     */
    public void setConnections(final List<ConnectionProperties> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
        rebuildIndex();
    }

    /**
     * Returns all configured connection profiles.
     *
     * @return an unmodifiable list of all profiles
     */
    public List<ConnectionProfile> findAll() {
        return Collections.unmodifiableList(new ArrayList<>(profilesByName.values()));
    }

    /**
     * Finds a connection profile by its unique name.
     *
     * @param name the profile name to search for
     * @return an Optional containing the profile if found
     */
    public Optional<ConnectionProfile> findByName(final String name) {
        return Optional.ofNullable(profilesByName.get(name));
    }

    /**
     * Checks if a connection profile exists with the given name.
     *
     * @param name the profile name to check
     * @return true if a profile exists with this name
     */
    public boolean exists(final String name) {
        return profilesByName.containsKey(name);
    }

    /**
     * Returns the number of configured connection profiles.
     *
     * @return the profile count
     */
    public int count() {
        return profilesByName.size();
    }

    /**
     * Registers a connection profile programmatically.
     *
     * <p>This is useful for testing or dynamic configuration.
     * If a profile with the same name already exists, it will be replaced.
     *
     * @param profile the connection profile to register
     */
    public void register(final ConnectionProfile profile) {
        profilesByName.put(profile.name(), profile);
    }

    /**
     * Removes a connection profile by name.
     *
     * @param name the profile name to remove
     * @return true if a profile was removed
     */
    public boolean unregister(final String name) {
        return profilesByName.remove(name) != null;
    }

    /**
     * Removes all connection profiles.
     */
    public void clear() {
        profilesByName.clear();
    }

    private void rebuildIndex() {
        profilesByName.clear();
        for (ConnectionProperties props : connections) {
            ConnectionProfile profile = props.toConnectionProfile();
            profilesByName.put(profile.name(), profile);
        }
    }

    /**
     * Configuration properties for a single database connection.
     *
     * <p>Used for Spring Boot configuration binding.
     */
    public static class ConnectionProperties {

        private String name;
        private DatabaseType type;
        private String host;
        private int port;
        private String database;
        private String username;
        private String password;
        private String schema;
        private boolean readOnly = true;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public DatabaseType getType() {
            return type;
        }

        public void setType(final DatabaseType type) {
            this.type = type;
        }

        public String getHost() {
            return host;
        }

        public void setHost(final String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(final int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(final String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(final String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(final String password) {
            this.password = password;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(final String schema) {
            this.schema = schema;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setReadOnly(final boolean readOnly) {
            this.readOnly = readOnly;
        }

        /**
         * Converts this properties object to a ConnectionProfile.
         *
         * @return the corresponding ConnectionProfile
         */
        public ConnectionProfile toConnectionProfile() {
            return ConnectionProfile.create(
                    name, type, host, port, database,
                    username, password, schema, readOnly
            );
        }

    }

}
