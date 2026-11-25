package co.fanki.sqlmcp.auth.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import co.fanki.sqlmcp.connection.domain.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AccessPolicy}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class AccessPolicyTest {

    private static final Set<String> ALL = Set.of("analytics", "billing", "ops");

    private static ConnectionProfileRepository repository(final String... names) {
        final ConnectionProfileRepository repository = new ConnectionProfileRepository();
        for (final String name : names) {
            repository.register(ConnectionProfile.create(
                    name, DatabaseType.POSTGRESQL, "localhost", 5432, "db", "u", "p", "public", true));
        }
        return repository;
    }

    private static AccessPolicy policy(final AccessPolicy.Entry... entries) {
        final AccessPolicy policy = new AccessPolicy(repository("analytics", "billing", "ops"));
        policy.setAccess(List.of(entries));
        return policy;
    }

    @Test
    void whenResolvingConnections_givenUserInTwoMappedGroups_shouldReturnUnion() {
        final AccessPolicy policy = policy(
                AccessPolicy.Entry.of("data@example.com", List.of("analytics")),
                AccessPolicy.Entry.of("sre@example.com", List.of("billing")));

        final Set<String> result = policy.connectionsFor(Set.of("data@example.com", "sre@example.com"), ALL);

        assertEquals(Set.of("analytics", "billing"), result);
    }

    @Test
    void whenResolvingConnections_givenWildcard_shouldReturnAllConnections() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.of("sre@example.com", List.of("*")));

        assertEquals(ALL, policy.connectionsFor(Set.of("sre@example.com"), ALL));
    }

    @Test
    void whenResolvingConnections_givenNoMatchingGroup_shouldReturnEmpty() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.of("data@example.com", List.of("analytics")));

        assertTrue(policy.connectionsFor(Set.of("other@example.com"), ALL).isEmpty());
    }

    @Test
    void whenResolvingConnections_givenGroupWithDifferentCase_shouldMatch() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.of("Data@Example.com", List.of("analytics")));

        assertEquals(Set.of("analytics"), policy.connectionsFor(Set.of("DATA@example.COM"), ALL));
    }

    @Test
    void whenValidating_givenUnknownConnectionInMapping_shouldFailNamingIt() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.of("data@example.com", List.of("analitics")));

        final IllegalStateException error = assertThrows(IllegalStateException.class, policy::validate);

        assertTrue(error.getMessage().contains("analitics"));
    }

    @Test
    void whenValidating_givenEntryWithBlankOrMissingGroup_shouldFail() {
        for (final String group : new String[] {null, "", "  "}) {
            final AccessPolicy policy = policy(AccessPolicy.Entry.of(group, List.of("analytics")));

            final IllegalStateException error = assertThrows(IllegalStateException.class, policy::validate);

            assertTrue(error.getMessage().contains("group"), error.getMessage());
        }
    }

}
