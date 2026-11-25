package co.fanki.sqlmcp.auth.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the redirect URI rules of {@link RegisteredClient} and the PKCE check of
 * {@link AuthorizationCode}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class RegisteredClientTest {

    @Test
    void whenRegistering_givenHttpsOrLoopbackUri_shouldAccept() {
        assertTrue(RegisteredClient.isAcceptableRedirectUri("https://app.example.com/cb"));
        assertTrue(RegisteredClient.isAcceptableRedirectUri("http://localhost:8123/cb"));
        assertTrue(RegisteredClient.isAcceptableRedirectUri("http://127.0.0.1/cb"));
    }

    @Test
    void whenRegistering_givenPlainHttpRemoteOrFragmentUri_shouldReject() {
        assertFalse(RegisteredClient.isAcceptableRedirectUri("http://app.example.com/cb"));
        assertFalse(RegisteredClient.isAcceptableRedirectUri("https://app.example.com/cb#frag"));
        assertFalse(RegisteredClient.isAcceptableRedirectUri("javascript:alert(1)"));
        assertFalse(RegisteredClient.isAcceptableRedirectUri("not a uri"));
    }

    @Test
    void whenMatchingRedirect_givenLoopbackWithDifferentPort_shouldAllow() {
        final RegisteredClient client = RegisteredClient.of("c", List.of("http://localhost:8123/cb"));

        assertTrue(client.allows("http://localhost:51000/cb"));
        assertFalse(client.allows("http://localhost:51000/other"));
    }

    @Test
    void whenMatchingRedirect_givenRemoteUriWithDifferentPath_shouldReject() {
        final RegisteredClient client = RegisteredClient.of("c", List.of("https://app.example.com/cb"));

        assertTrue(client.allows("https://app.example.com/cb"));
        assertFalse(client.allows("https://app.example.com/other"));
        assertFalse(client.allows("https://evil.example.com/cb"));
    }

    @Test
    void whenCheckingVerifier_givenRfc7636Example_shouldMatchOnlyTheRightVerifier() {
        final AuthorizationCode code = AuthorizationCode.of(
                "a@example.com", "example.com", "c", "https://app.example.com/cb",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");

        assertTrue(code.matchesVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertFalse(code.matchesVerifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXj"));
    }

    @Test
    void whenRegistering_givenUriWithUserInfo_shouldReject() {
        assertFalse(RegisteredClient.isAcceptableRedirectUri("https://user:pw@app.example.com/cb"));
        assertFalse(RegisteredClient.isAcceptableRedirectUri("http://user@localhost/cb"));
    }

    @Test
    void whenMatchingRedirect_givenRequestedUriWithFragmentOrUserInfo_shouldReject() {
        final RegisteredClient client = RegisteredClient.of("c", List.of("http://localhost:8123/cb"));

        assertFalse(client.allows("http://localhost:51000/cb#x"));
        assertFalse(client.allows("http://user@localhost:51000/cb"));
    }

    @Test
    void whenCheckingPkceValue_givenUnreservedCharactersOfAllowedLength_shouldAccept() {
        assertTrue(AuthorizationCode.isPkceValue("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"));
        assertTrue(AuthorizationCode.isPkceValue("a".repeat(128)));
    }

    @Test
    void whenCheckingPkceValue_givenTooShortTooLongOrReservedCharacters_shouldReject() {
        assertFalse(AuthorizationCode.isPkceValue("a".repeat(42)));
        assertFalse(AuthorizationCode.isPkceValue("a".repeat(129)));
        assertFalse(AuthorizationCode.isPkceValue("a".repeat(42) + "!"));
        assertFalse(AuthorizationCode.isPkceValue("a".repeat(42) + " "));
        assertFalse(AuthorizationCode.isPkceValue(null));
    }

}
