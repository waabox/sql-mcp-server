package co.fanki.sqlmcp.auth.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import co.fanki.sqlmcp.connection.domain.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConnectionAccess}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class ConnectionAccessTest {

    private static ConnectionAccess access(final GroupResolver resolver) {
        return access(resolver, AccessPolicy.Entry.ofGroup("data@example.com", List.of("analytics")));
    }

    private static ConnectionAccess access(final GroupResolver resolver, final AccessPolicy.Entry entry) {
        final ConnectionProfileRepository repository = new ConnectionProfileRepository();
        for (final String name : List.of("analytics", "billing")) {
            repository.register(ConnectionProfile.create(
                    name, DatabaseType.POSTGRESQL, "localhost", 5432, "db", "u", "p", "public", true));
        }
        final AccessPolicy policy = new AccessPolicy(repository);
        policy.setAccess(List.of(entry));
        return new ConnectionAccess(repository, policy, resolver);
    }

    private static List<String> names(final List<ConnectionProfile> profiles) {
        return profiles.stream().map(ConnectionProfile::name).sorted().toList();
    }

    @Test
    void whenCheckingAccess_givenSharedTokenCaller_shouldAllowEveryConnection() {
        final ConnectionAccess access = access(email -> Set.of());

        assertEquals(List.of("analytics", "billing"), names(access.visibleConnections(Caller.sharedToken())));
        assertTrue(access.canUse(Caller.sharedToken(), "billing"));
        assertTrue(access.canUse(Caller.anonymous(), "billing"));
        assertTrue(access.canUse(Caller.local(), "analytics"));
        assertFalse(access.canUse(Caller.sharedToken(), "does-not-exist"));
    }

    @Test
    void whenCheckingAccess_givenGoogleUserWithoutGroups_shouldDenyEveryConnection() {
        final ConnectionAccess access = access(email -> Set.of());
        final Caller caller = Caller.googleUser("ana@example.com");

        assertTrue(access.visibleConnections(caller).isEmpty());
        assertFalse(access.canUse(caller, "analytics"));
    }

    @Test
    void whenCheckingAccess_givenGoogleUserInMappedGroup_shouldAllowOnlyMappedConnections() {
        final ConnectionAccess access = access(email -> Set.of("data@example.com"));
        final Caller caller = Caller.googleUser("ana@example.com");

        assertEquals(List.of("analytics"), names(access.visibleConnections(caller)));
        assertTrue(access.canUse(caller, "analytics"));
        assertFalse(access.canUse(caller, "billing"));
    }

    @Test
    void whenCheckingAccess_givenGroupResolutionFails_shouldDenyEveryConnection() {
        final ConnectionAccess access = access(email -> {
            throw new GroupResolutionException("Google is down");
        });
        final Caller caller = Caller.googleUser("ana@example.com");

        assertTrue(access.visibleConnections(caller).isEmpty());
        assertFalse(access.canUse(caller, "analytics"));
    }

    @Test
    void whenCheckingAccess_givenUsersOnlyPolicy_shouldNotCallGroupResolver() {
        final ConnectionAccess access = access(
                email -> {
                    throw new AssertionError("the group resolver must not be called");
                },
                AccessPolicy.Entry.ofUsers(List.of("Ana@example.com"), List.of("analytics")));

        assertEquals(List.of("analytics"), names(access.visibleConnections(Caller.googleUser("ana@example.com"))));
        assertTrue(access.canUse(Caller.googleUser("ana@example.com"), "analytics"));
        assertFalse(access.canUse(Caller.googleUser("pedro@example.com"), "analytics"));
    }

}
