package co.fanki.sqlmcp.auth.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuthSettings} startup validation.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class AuthSettingsTest {

    private static AuthSettings complete() {
        final AuthSettings settings = new AuthSettings();
        settings.setIssuer("https://sql-mcp.example.com");
        settings.setSigningKey("0123456789abcdef0123456789abcdef");
        settings.getGoogle().setEnabled(true);
        settings.getGoogle().setClientId("client-id");
        settings.getGoogle().setClientSecret("client-secret");
        settings.getGoogle().setAllowedDomains(List.of("example.com"));
        settings.getGoogle().setServiceAccountKey("{}");
        settings.getGoogle().setAdminEmail("admin@example.com");
        return settings;
    }

    @Test
    void whenStarting_givenGoogleDisabled_shouldNotRequireAnything() {
        assertDoesNotThrow(() -> new AuthSettings().validate());
    }

    @Test
    void whenStarting_givenGoogleEnabledAndEverythingSet_shouldPass() {
        assertDoesNotThrow(() -> complete().validate());
    }

    @Test
    void whenStarting_givenGoogleEnabledAndMissingAdminEmail_shouldFailNamingTheKey() {
        final AuthSettings settings = complete();
        settings.getGoogle().setAdminEmail(" ");

        final IllegalStateException error = assertThrows(IllegalStateException.class, settings::validate);

        assertTrue(error.getMessage().contains("sql-mcp.auth.google.admin-email"), error.getMessage());
    }

    @Test
    void whenStarting_givenGoogleEnabledAndShortSigningKey_shouldFailNamingTheKey() {
        final AuthSettings settings = complete();
        settings.setSigningKey("too-short");

        final IllegalStateException error = assertThrows(IllegalStateException.class, settings::validate);

        assertTrue(error.getMessage().contains("sql-mcp.auth.signing-key"), error.getMessage());
    }

}
