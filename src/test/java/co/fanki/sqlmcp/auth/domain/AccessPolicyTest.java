package co.fanki.sqlmcp.auth.domain;

import co.fanki.sqlmcp.connection.domain.ConnectionProfile;
import co.fanki.sqlmcp.connection.domain.ConnectionProfileRepository;
import co.fanki.sqlmcp.connection.domain.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                AccessPolicy.Entry.ofGroup("data@example.com", List.of("analytics")),
                AccessPolicy.Entry.ofGroup("sre@example.com", List.of("billing")));

        final Set<String> result = policy.connectionsFor("ana@example.com", Set.of("data@example.com", "sre@example.com"), ALL);

        assertEquals(Set.of("analytics", "billing"), result);
    }

    @Test
    void whenResolvingConnections_givenWildcard_shouldReturnAllConnections() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.ofGroup("sre@example.com", List.of("*")));

        assertEquals(ALL, policy.connectionsFor("ana@example.com", Set.of("sre@example.com"), ALL));
    }

    @Test
    void whenResolvingConnections_givenNoMatchingGroup_shouldReturnEmpty() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.ofGroup("data@example.com", List.of("analytics")));

        assertTrue(policy.connectionsFor("ana@example.com", Set.of("other@example.com"), ALL).isEmpty());
    }

    @Test
    void whenResolvingConnections_givenGroupWithDifferentCase_shouldMatch() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.ofGroup("Data@Example.com", List.of("analytics")));

        assertEquals(Set.of("analytics"), policy.connectionsFor("ana@example.com", Set.of("DATA@example.COM"), ALL));
    }

    @Test
    void whenValidating_givenUnknownConnectionInMapping_shouldFailNamingIt() {
        final AccessPolicy policy = policy(AccessPolicy.Entry.ofGroup("data@example.com", List.of("analitics")));

        final IllegalStateException error = assertThrows(IllegalStateException.class, policy::validate);

        assertTrue(error.getMessage().contains("analitics"));
    }

    @Test
    void whenValidating_givenEntryWithBlankOrMissingGroup_shouldFail() {
        for (final String group : new String[] {null, "", "  "}) {
            final AccessPolicy policy = policy(AccessPolicy.Entry.ofGroup(group, List.of("analytics")));

            final IllegalStateException error = assertThrows(IllegalStateException.class, policy::validate);

            assertTrue(error.getMessage().contains("group"), error.getMessage());
        }
    }

    @Test
    void whenResolvingConnections_givenListedUser_shouldReturnEntryConnections() {
        final AccessPolicy policy = policy(
                AccessPolicy.Entry.ofUsers(List.of("ana@example.com", "pedro@example.com"), List.of("analytics")));

        assertEquals(Set.of("analytics"), policy.connectionsFor("pedro@example.com", Set.of(), ALL));
        assertTrue(policy.connectionsFor("other@example.com", Set.of(), ALL).isEmpty());
    }

    @Test
    void whenResolvingConnections_givenListedUserWithDifferentCase_shouldMatch() {
        final AccessPolicy policy = policy(
                AccessPolicy.Entry.ofUsers(List.of("Ana@Example.com"), List.of("analytics")));

        assertEquals(Set.of("analytics"), policy.connectionsFor("ANA@example.COM", Set.of(), ALL));
    }

    @Test
    void whenResolvingConnections_givenUserListedAndInMappedGroup_shouldReturnUnion() {
        final AccessPolicy policy = policy(
                AccessPolicy.Entry.ofUsers(List.of("ana@example.com"), List.of("analytics")),
                AccessPolicy.Entry.ofGroup("sre@example.com", List.of("billing")));

        assertEquals(Set.of("analytics", "billing"),
                policy.connectionsFor("ana@example.com", Set.of("sre@example.com"), ALL));
    }

    @Test
    void whenCheckingUsesGroups_givenOnlyUserEntries_shouldBeFalse() {
        assertFalse(policy(AccessPolicy.Entry.ofUsers(List.of("ana@example.com"), List.of("*"))).usesGroups());
        assertTrue(policy(AccessPolicy.Entry.ofGroup("sre@example.com", List.of("*"))).usesGroups());
        assertFalse(policy().usesGroups());
    }

    @Test
    void whenValidating_givenEntryWithGroupAndUsers_shouldFail() {
        final AccessPolicy.Entry entry = AccessPolicy.Entry.ofGroup("data@example.com", List.of("analytics"));
        entry.setUsers(List.of("ana@example.com"));

        final IllegalStateException error =
                assertThrows(IllegalStateException.class, policy(entry)::validate);

        assertTrue(error.getMessage().contains("both"), error.getMessage());
    }

    @Test
    void whenValidating_givenEntryWithNeitherGroupNorUsers_shouldFail() {
        final AccessPolicy.Entry entry = AccessPolicy.Entry.ofUsers(List.of(), List.of("analytics"));

        final IllegalStateException error =
                assertThrows(IllegalStateException.class, policy(entry)::validate);

        assertTrue(error.getMessage().contains("analytics"), error.getMessage());
        assertTrue(error.getMessage().contains("group"), error.getMessage());
        assertTrue(error.getMessage().contains("users"), error.getMessage());
    }

    @Test
    void whenValidating_givenBlankOrInvalidUserEmail_shouldFail() {
        for (final String user : new String[] {"", "  ", "not-an-email"}) {
            final AccessPolicy policy = policy(
                    AccessPolicy.Entry.ofUsers(List.of("ana@example.com", user), List.of("analytics")));

            final IllegalStateException error = assertThrows(IllegalStateException.class, policy::validate);

            assertTrue(error.getMessage().contains("users"), error.getMessage());
        }
    }

}
