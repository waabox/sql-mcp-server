package co.fanki.sqlmcp.auth.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps groups to the connection profiles their members may use.
 *
 * <p>Business rules: the connections of a user are the union of the connections of every entry whose group the
 * user belongs to; a group is matched case-insensitively; {@code "*"} means every configured connection; an
 * entry that names a connection that does not exist makes the application fail at startup.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp")
public class AccessPolicy implements InitializingBean {

    /** The wildcard that grants every connection. */
    public static final String ALL_CONNECTIONS = "*";

    private final ConnectionProfileRepository profileRepository;
    private List<Entry> access = new ArrayList<>();

    /**
     * Creates the policy.
     *
     * @param profileRepository the repository used to validate the configured connection names
     */
    public AccessPolicy(final ConnectionProfileRepository profileRepository) {
        this.profileRepository = Objects.requireNonNull(profileRepository);
    }

    /**
     * Sets the group to connections mapping; called by Spring when binding {@code sql-mcp.access}.
     *
     * @param access the entries; null is treated as empty
     */
    public void setAccess(final List<Entry> access) {
        this.access = access != null ? new ArrayList<>(access) : new ArrayList<>();
    }

    /**
     * Returns the configured entries.
     *
     * @return the entries
     */
    public List<Entry> getAccess() {
        return access;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * Checks that every connection named in the mapping exists.
     *
     * @throws IllegalStateException naming the first unknown connection
     */
    public void validate() {
        for (final Entry entry : access) {
            for (final String name : entry.getConnections()) {
                if (!ALL_CONNECTIONS.equals(name) && !profileRepository.exists(name)) {
                    throw new IllegalStateException("sql-mcp.access names the connection '" + name
                            + "' (group '" + entry.getGroup() + "'), which is not configured");
                }
            }
        }
    }

    /**
     * Returns the connections a member of the given groups may use.
     *
     * @param groups the groups of the user
     * @param allConnectionNames every configured connection name
     * @return the allowed connection names, possibly empty
     */
    public Set<String> connectionsFor(final Set<String> groups, final Set<String> allConnectionNames) {
        final Set<String> lowerGroups = new HashSet<>();
        for (final String group : groups) {
            lowerGroups.add(group.toLowerCase(Locale.ROOT));
        }
        final Set<String> allowed = new TreeSet<>();
        for (final Entry entry : access) {
            if (entry.getGroup() == null) {
                continue;
            }
            if (!lowerGroups.contains(entry.getGroup().strip().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (final String name : entry.getConnections()) {
                if (ALL_CONNECTIONS.equals(name)) {
                    allowed.addAll(allConnectionNames);
                } else if (allConnectionNames.contains(name)) {
                    allowed.add(name);
                }
            }
        }
        return allowed;
    }

    /** One mapping from a group to connections; used for configuration binding. */
    public static class Entry {

        private String group;
        private List<String> connections = new ArrayList<>();

        /**
         * Creates an entry.
         *
         * @param group the group email
         * @param connections the connection names, or {@code "*"}
         * @return the entry
         */
        public static Entry of(final String group, final List<String> connections) {
            final Entry entry = new Entry();
            entry.setGroup(group);
            entry.setConnections(connections);
            return entry;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(final String group) {
            this.group = group;
        }

        public List<String> getConnections() {
            return connections;
        }

        public void setConnections(final List<String> connections) {
            this.connections = connections != null ? new ArrayList<>(connections) : new ArrayList<>();
        }

    }

}
