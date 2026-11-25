package co.fanki.sqlmcp.auth.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which connection profiles a caller may use.
 *
 * <p>Business rules: local, anonymous and shared-token callers may use every connection; a Google user may use
 * the connections that the access policy grants to the user or to the user's groups (groups are only resolved when
 * the policy maps some), and none when there is no match or when the
 * groups cannot be resolved (fail closed).
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class ConnectionAccess {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionAccess.class);

    private final ConnectionProfileRepository profileRepository;
    private final AccessPolicy accessPolicy;
    private final GroupResolver groupResolver;

    /**
     * Creates the component.
     *
     * @param profileRepository the connection profiles
     * @param accessPolicy the group to connections mapping
     * @param groupResolver resolves the groups of a Google user
     */
    public ConnectionAccess(
            final ConnectionProfileRepository profileRepository,
            final AccessPolicy accessPolicy,
            final GroupResolver groupResolver) {
        this.profileRepository = Objects.requireNonNull(profileRepository);
        this.accessPolicy = Objects.requireNonNull(accessPolicy);
        this.groupResolver = Objects.requireNonNull(groupResolver);
    }

    /**
     * Returns the connections the caller may use.
     *
     * @param caller the caller
     * @return the visible connection profiles
     */
    public List<ConnectionProfile> visibleConnections(final Caller caller) {
        final List<ConnectionProfile> all = profileRepository.findAll();
        if (caller.kind() != Caller.Kind.GOOGLE_USER) {
            return all;
        }
        final Set<String> allowed = allowedNames(caller, all);
        return all.stream().filter(profile -> allowed.contains(profile.name())).toList();
    }

    /**
     * Tells whether the caller may use a connection.
     *
     * @param caller the caller
     * @param connectionName the connection name
     * @return true when the connection exists and the caller may use it
     */
    public boolean canUse(final Caller caller, final String connectionName) {
        if (connectionName == null || !profileRepository.exists(connectionName)) {
            return false;
        }
        if (caller.kind() != Caller.Kind.GOOGLE_USER) {
            return true;
        }
        return allowedNames(caller, profileRepository.findAll()).contains(connectionName);
    }

    private Set<String> allowedNames(final Caller caller, final List<ConnectionProfile> all) {
        final Set<String> allNames = all.stream().map(ConnectionProfile::name).collect(Collectors.toSet());
        final String email = caller.email().orElseThrow();
        Set<String> groups = Set.of();
        if (accessPolicy.usesGroups()) {
            try {
                groups = groupResolver.groupsOf(email);
            } catch (final GroupResolutionException e) {
                LOG.warn("Denying access to {}: groups could not be resolved: {}", email, e.getMessage());
                return Set.of();
            }
        }
        return accessPolicy.connectionsFor(email, groups, allNames);
    }

}
