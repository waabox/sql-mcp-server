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
 * Maps groups and explicit users to the connection profiles they may use.
 *
 * <p>Business rules: an entry has exactly one of a group or a list of user emails; the connections of a user are
 * the union of the connections of every entry that lists the user or whose group the user belongs to; groups and
 * emails are matched case-insensitively; {@code "*"} means every configured connection; an entry that names a
 * connection that does not exist, has both or neither of group and users, or lists an invalid email makes the
 * application fail at startup.
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

    /**
     * Validates the mapping once Spring has bound it, so an invalid configuration prevents startup.
     *
     * @throws IllegalStateException if the mapping is invalid (see {@link #validate()})
     */
    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * Checks that every connection named in the mapping exists.
     *
     * @throws IllegalStateException naming the first unknown connection, an entry with both or neither of group and
     *     users, or an invalid user email
     */
    public void validate() {
        for (final Entry entry : access) {
            final boolean hasGroup = entry.getGroup() != null && !entry.getGroup().isBlank();
            final boolean hasUsers = !entry.getUsers().isEmpty();
            final String label;
            if (hasGroup && hasUsers) {
                throw new IllegalStateException("sql-mcp.access has an entry with both a group ('"
                        + entry.getGroup() + "') and users; use exactly one (connections "
                        + entry.getConnections() + ")");
            } else if (hasGroup) {
                label = "group '" + entry.getGroup() + "'";
            } else if (hasUsers) {
                label = "users " + entry.getUsers();
                for (final String user : entry.getUsers()) {
                    if (user == null || user.isBlank() || !user.contains("@")) {
                        throw new IllegalStateException("sql-mcp.access has a users entry with the invalid email '"
                                + user + "' (connections " + entry.getConnections() + ")");
                    }
                }
            } else {
                throw new IllegalStateException(
                        "sql-mcp.access has an entry with neither a group nor users (connections "
                                + entry.getConnections() + ")");
            }
            for (final String name : entry.getConnections()) {
                if (!ALL_CONNECTIONS.equals(name) && !profileRepository.exists(name)) {
                    throw new IllegalStateException("sql-mcp.access names the connection '" + name
                            + "' (" + label + "), which is not configured");
                }
            }
        }
    }

    /**
     * Tells whether some entry maps a group, so group membership must be resolved.
     *
     * @return true when at least one entry uses a group
     */
    public boolean usesGroups() {
        for (final Entry entry : access) {
            if (entry.getGroup() != null && !entry.getGroup().isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the connections a member of the given groups may use.
     *
     * @param email the email of the user
     * @param groups the groups of the user
     * @param allConnectionNames every configured connection name
     * @return the allowed connection names, possibly empty
     */
    public Set<String> connectionsFor(
            final String email, final Set<String> groups, final Set<String> allConnectionNames) {
        final Set<String> lowerGroups = new HashSet<>();
        for (final String group : groups) {
            lowerGroups.add(group.toLowerCase(Locale.ROOT));
        }
        final String lowerEmail = email.strip().toLowerCase(Locale.ROOT);
        final Set<String> allowed = new TreeSet<>();
        for (final Entry entry : access) {
            if (!entry.matches(lowerEmail, lowerGroups)) {
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
        private List<String> users = new ArrayList<>();
        private List<String> connections = new ArrayList<>();

        /**
         * Creates an entry that grants connections to the members of a group.
         *
         * @param group the group email
         * @param connections the connection names, or {@code "*"}
         * @return the entry
         */
        public static Entry ofGroup(final String group, final List<String> connections) {
            final Entry entry = new Entry();
            entry.setGroup(group);
            entry.setConnections(connections);
            return entry;
        }

        /**
         * Creates an entry that grants connections to explicit users.
         *
         * @param users the user emails
         * @param connections the connection names, or {@code "*"}
         * @return the entry
         */
        public static Entry ofUsers(final List<String> users, final List<String> connections) {
            final Entry entry = new Entry();
            entry.setUsers(users);
            entry.setConnections(connections);
            return entry;
        }

        /**
         * Returns the users.
         *
         * @return the user emails, possibly empty
         */
        public List<String> getUsers() {
            return users;
        }

        /**
         * Sets the users; called by Spring when binding the configuration.
         *
         * @param users the user emails
         */
        public void setUsers(final List<String> users) {
            this.users = users != null ? new ArrayList<>(users) : new ArrayList<>();
        }

        private boolean matches(final String lowerEmail, final Set<String> lowerGroups) {
            if (group != null && lowerGroups.contains(group.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
            for (final String user : users) {
                if (user != null && user.strip().toLowerCase(Locale.ROOT).equals(lowerEmail)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns the group.
         *
         * @return the group
         */
        public String getGroup() {
            return group;
        }

        /**
         * Sets the group; called by Spring when binding the configuration.
         *
         * @param group the group
         */
        public void setGroup(final String group) {
            this.group = group;
        }

        /**
         * Returns the connections.
         *
         * @return the connections
         */
        public List<String> getConnections() {
            return connections;
        }

        /**
         * Sets the connections; called by Spring when binding the configuration.
         *
         * @param connections the connections
         */
        public void setConnections(final List<String> connections) {
            this.connections = connections != null ? new ArrayList<>(connections) : new ArrayList<>();
        }

    }

}
