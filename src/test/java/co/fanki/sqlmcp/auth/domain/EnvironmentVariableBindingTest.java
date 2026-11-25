package co.fanki.sqlmcp.auth.domain;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that the environment variable names documented in the README bind to the list settings of Google login.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class EnvironmentVariableBindingTest {

    private static Binder binderFor(final Map<String, Object> variables) {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment", variables));
        ConfigurationPropertySources.attach(environment);
        return Binder.get(environment);
    }

    @Test
    void whenBindingAllowedDomains_givenDocumentedEnvironmentVariables_shouldBindTheList() {
        final Binder binder = binderFor(Map.of(
                "SQL_MCP_AUTH_GOOGLE_ALLOWED_DOMAINS_0", "example.com",
                "SQL_MCP_AUTH_GOOGLE_ALLOWED_DOMAINS_1", "other.com"));

        final AuthSettings settings = binder.bind("sql-mcp.auth", Bindable.of(AuthSettings.class))
                .orElseGet(AuthSettings::new);

        assertEquals(List.of("example.com", "other.com"), settings.getGoogle().getAllowedDomains());
    }

    @Test
    void whenBindingAccess_givenDocumentedEnvironmentVariables_shouldBindGroupsAndConnections() {
        final Binder binder = binderFor(Map.of(
                "SQL_MCP_ACCESS_0_GROUP", "data@example.com",
                "SQL_MCP_ACCESS_0_CONNECTIONS_0", "analytics",
                "SQL_MCP_ACCESS_0_CONNECTIONS_1", "billing",
                "SQL_MCP_ACCESS_1_GROUP", "sre@example.com",
                "SQL_MCP_ACCESS_1_CONNECTIONS_0", "*"));

        final List<AccessPolicy.Entry> entries = binder
                .bind("sql-mcp.access", Bindable.listOf(AccessPolicy.Entry.class)).get();

        assertEquals(2, entries.size());
        assertEquals("data@example.com", entries.get(0).getGroup());
        assertEquals(List.of("analytics", "billing"), entries.get(0).getConnections());
        assertEquals("sre@example.com", entries.get(1).getGroup());
        assertEquals(List.of("*"), entries.get(1).getConnections());
    }

}
