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

    private static void assertIssuerRejected(final String issuer) {
        final AuthSettings settings = complete();
        settings.setIssuer(issuer);

        final IllegalStateException error = assertThrows(IllegalStateException.class, settings::validate);

        assertTrue(error.getMessage().contains("sql-mcp.auth.issuer"), issuer + ": " + error.getMessage());
    }

    @Test
    void whenStarting_givenIssuerThatIsNotAnAbsoluteUrl_shouldFailNamingTheKey() {
        assertIssuerRejected("sql-mcp.example.com");
        assertIssuerRejected("/relative");
        assertIssuerRejected("ftp://sql-mcp.example.com");
        assertIssuerRejected("https://");
    }

    @Test
    void whenStarting_givenIssuerWithPathQueryOrFragment_shouldFailNamingTheKey() {
        assertIssuerRejected("https://sql-mcp.example.com/sqlmcp");
        assertIssuerRejected("https://sql-mcp.example.com?x=1");
        assertIssuerRejected("https://sql-mcp.example.com/#frag");
        assertIssuerRejected("https://user@sql-mcp.example.com");
    }

    @Test
    void whenStarting_givenPlainHttpIssuerOutsideLoopback_shouldFailNamingTheKey() {
        assertIssuerRejected("http://sql-mcp.example.com");
    }

    @Test
    void whenStarting_givenHttpsOrLoopbackIssuer_shouldPass() {
        for (final String issuer : List.of("https://sql-mcp.example.com", "https://sql-mcp.example.com/",
                "https://sql-mcp.example.com:8443", "http://localhost:8080", "http://127.0.0.1:8080")) {
            final AuthSettings settings = complete();
            settings.setIssuer(issuer);

            assertDoesNotThrow(settings::validate, issuer);
        }
    }

}
