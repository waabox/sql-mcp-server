package co.fanki.sqlmcp.auth.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link TokenService}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class TokenServiceTest {

    private static final String ISSUER = "https://sql-mcp.example.com";
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private MutableClock clock;
    private TokenService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        service = serviceWithKey(KEY);
    }

    private TokenService serviceWithKey(final String key) {
        final AuthSettings settings = new AuthSettings();
        settings.setIssuer(ISSUER);
        settings.setSigningKey(key);
        return new TokenService(settings, clock);
    }

    @Test
    void whenVerifyingAccessToken_givenFreshToken_shouldReturnEmail() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");

        assertEquals("ana@example.com", service.verifyAccessToken(tokens.accessToken()));
        assertEquals(3600L, tokens.expiresInSeconds());
    }

    @Test
    void whenVerifyingAccessToken_givenExpiredToken_shouldThrow() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThrows(InvalidTokenException.class, () -> service.verifyAccessToken(tokens.accessToken()));
    }

    @Test
    void whenVerifyingAccessToken_givenRefreshToken_shouldThrow() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");

        assertThrows(InvalidTokenException.class, () -> service.verifyAccessToken(tokens.refreshToken()));
    }

    @Test
    void whenReadingRefreshToken_givenAccessToken_shouldThrow() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");

        assertThrows(InvalidTokenException.class, () -> service.readRefreshToken(tokens.accessToken()));
    }

    @Test
    void whenReadingRefreshToken_givenFreshToken_shouldReturnEmailAndClient() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");

        final RefreshGrant grant = service.readRefreshToken(tokens.refreshToken());

        assertEquals("ana@example.com", grant.email());
        assertEquals("client-1", grant.clientId());
    }

    @Test
    void whenRedeemingCode_givenAccessToken_shouldThrow() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");

        assertThrows(InvalidTokenException.class, () -> service.redeemAuthorizationCode(tokens.accessToken()));
    }

    @Test
    void whenRedeemingCode_givenFreshCode_shouldReturnItsContents() {
        final String code = service.issueAuthorizationCode(
                AuthorizationCode.of("ana@example.com", "client-1", "http://localhost:1/cb", "challenge"));

        final AuthorizationCode redeemed = service.redeemAuthorizationCode(code);

        assertEquals("ana@example.com", redeemed.email());
        assertEquals("client-1", redeemed.clientId());
        assertEquals("http://localhost:1/cb", redeemed.redirectUri());
        assertEquals("challenge", redeemed.codeChallenge());
    }

    @Test
    void whenRedeemingCode_givenOlderThanSixtySeconds_shouldThrow() {
        final String code = service.issueAuthorizationCode(
                AuthorizationCode.of("ana@example.com", "client-1", "http://localhost:1/cb", "challenge"));
        clock.advance(Duration.ofSeconds(61));

        assertThrows(InvalidTokenException.class, () -> service.redeemAuthorizationCode(code));
    }

    @Test
    void whenReadingToken_givenOtherSigningKey_shouldThrow() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");
        final TokenService other = serviceWithKey("ffffffffffffffffffffffffffffffff");

        assertThrows(InvalidTokenException.class, () -> other.verifyAccessToken(tokens.accessToken()));
    }

    @Test
    void whenReadingToken_givenGarbage_shouldThrow() {
        assertThrows(InvalidTokenException.class, () -> service.verifyAccessToken("not-a-token"));
    }

    @Test
    void whenVerifyingAccessToken_givenNoSigningKeyConfigured_shouldThrowInvalidToken() {
        final TokenService unconfigured = new TokenService(new AuthSettings(), clock);

        assertThrows(InvalidTokenException.class, () -> unconfigured.verifyAccessToken("anything"));
    }

    @Test
    void whenReadingToken_givenOtherIssuer_shouldThrow() {
        final IssuedTokens tokens = service.issueTokens("ana@example.com", "client-1");
        final AuthSettings settings = new AuthSettings();
        settings.setIssuer("https://other.example.com");
        settings.setSigningKey(KEY);
        final TokenService other = new TokenService(settings, clock);

        assertThrows(InvalidTokenException.class, () -> other.verifyAccessToken(tokens.accessToken()));
    }

    @Test
    void whenReadingClient_givenRegisteredClient_shouldReturnSameRedirectUris() {
        final String clientId = service.registerClient(
                RegisteredClient.of("My Client", List.of("http://localhost:1/cb", "https://app.example.com/cb")));
        clock.advance(Duration.ofDays(3650));

        final RegisteredClient client = service.readClient(clientId);

        assertEquals("My Client", client.clientName());
        assertEquals(List.of("http://localhost:1/cb", "https://app.example.com/cb"), client.redirectUris());
    }

    @Test
    void whenReadingLoginState_givenIssuedState_shouldReturnItsContents() {
        final String token = service.issueLoginState(
                LoginState.of("client-1", "http://localhost:1/cb", "client-state", "challenge")
                        .withEmail("ana@example.com"));

        final LoginState state = service.readLoginState(token);

        assertEquals("client-1", state.clientId());
        assertEquals("http://localhost:1/cb", state.redirectUri());
        assertEquals("client-state", state.clientState());
        assertEquals("challenge", state.codeChallenge());
        assertEquals("ana@example.com", state.email());
    }

    @Test
    void whenReadingLoginState_givenOlderThanTenMinutes_shouldThrow() {
        final String token = service.issueLoginState(LoginState.of("c", "http://localhost:1/cb", null, "ch"));
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThrows(InvalidTokenException.class, () -> service.readLoginState(token));
    }

    /** A clock whose time is advanced by the test. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(final Instant now) {
            this.now = now;
        }

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

}
