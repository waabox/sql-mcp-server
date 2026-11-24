package co.fanki.sqlmcp.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link GoogleLogin} identity validation.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GoogleLoginTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private GoogleLogin login(final List<String> allowedDomains) {
        final AuthSettings settings = new AuthSettings();
        settings.getGoogle().setClientId("client-id");
        settings.getGoogle().setAllowedDomains(allowedDomains);
        return new GoogleLogin(settings, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Map<String, Object> validClaims() {
        final Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", "client-id");
        claims.put("exp", NOW.getEpochSecond() + 3600);
        claims.put("email", "Ana@Example.com");
        claims.put("email_verified", true);
        claims.put("hd", "example.com");
        return claims;
    }

    @Test
    void whenValidatingIdToken_givenValidClaims_shouldReturnEmail() {
        assertEquals("ana@example.com", login(List.of("example.com")).validateIdToken(validClaims()));
    }

    @Test
    void whenValidatingIdToken_givenVerifiedEmailWithoutHdClaim_shouldReject() {
        final Map<String, Object> claims = validClaims();
        claims.remove("hd");

        assertThrows(GoogleLoginException.class, () -> login(List.of("example.com")).validateIdToken(claims));
    }

    @Test
    void whenValidatingIdToken_givenHdNotInAllowedDomains_shouldReject() {
        final Map<String, Object> claims = validClaims();
        claims.put("hd", "other.com");

        assertThrows(GoogleLoginException.class, () -> login(List.of("example.com")).validateIdToken(claims));
    }

    @Test
    void whenValidatingIdToken_givenEmptyAllowedDomains_shouldReject() {
        assertThrows(GoogleLoginException.class, () -> login(List.of()).validateIdToken(validClaims()));
    }

    @Test
    void whenValidatingIdToken_givenWrongAudience_shouldReject() {
        final Map<String, Object> claims = validClaims();
        claims.put("aud", "someone-else");

        assertThrows(GoogleLoginException.class, () -> login(List.of("example.com")).validateIdToken(claims));
    }

    @Test
    void whenValidatingIdToken_givenUnverifiedEmail_shouldReject() {
        final Map<String, Object> claims = validClaims();
        claims.put("email_verified", false);

        assertThrows(GoogleLoginException.class, () -> login(List.of("example.com")).validateIdToken(claims));
    }

    @Test
    void whenValidatingIdToken_givenExpiredToken_shouldReject() {
        final Map<String, Object> claims = validClaims();
        claims.put("exp", NOW.getEpochSecond() - 1);

        assertThrows(GoogleLoginException.class, () -> login(List.of("example.com")).validateIdToken(claims));
    }

    @Test
    void whenValidatingIdToken_givenWrongIssuer_shouldReject() {
        final Map<String, Object> claims = validClaims();
        claims.put("iss", "https://evil.example");

        assertThrows(GoogleLoginException.class, () -> login(List.of("example.com")).validateIdToken(claims));
    }

}
