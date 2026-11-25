package co.fanki.sqlmcp.auth.domain;

import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWEHeader;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jwt.EncryptedJWT;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues and reads every token of the OAuth flow without storing anything.
 *
 * <p>Business rules: each token is a compact JWE (direct encryption, A256GCM) whose key is the SHA-256 of the
 * configured signing key, so any replica sharing the key can read it and clients cannot. Every token has a type
 * ({@code client}, {@code state}, {@code code}, {@code access}, {@code refresh}) that readers check, so a token can
 * never be used in place of another type. Readers also check the issuer and the expiry; client ids never expire.
 * Access tokens also carry the issuer as audience. Without a signing key (Google login disabled) nothing can be
 * issued and every token is rejected.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class TokenService implements AccessTokenVerifier {

    private static final String TYPE = "typ";
    private static final String CLIENT = "client";
    private static final String STATE = "state";
    private static final String CODE = "code";
    private static final String ACCESS = "access";
    private static final String REFRESH = "refresh";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration CODE_TTL = Duration.ofSeconds(60);

    private final String issuer;
    private final byte[] key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param settings the authentication settings (issuer, signing key, token lifetimes)
     * @param clock the clock used for issuing and expiring tokens
     */
    public TokenService(final AuthSettings settings, final Clock clock) {
        this.issuer = settings.getIssuer();
        this.key = settings.getSigningKey().isBlank() ? null : sha256(settings.getSigningKey());
        this.accessTtl = settings.getAccessTokenTtl();
        this.refreshTtl = settings.getRefreshTokenTtl();
        this.clock = clock;
    }

    /**
     * Registers a client.
     *
     * <p>Business rule: the client id is the encrypted registration itself and never expires.
     *
     * @param client the client to register
     * @return the client id
     */
    public String registerClient(final RegisteredClient client) {
        final JWTClaimsSet.Builder claims = base(CLIENT, null);
        claims.claim("name", client.clientName());
        claims.claim("uris", client.redirectUris());
        return encrypt(claims);
    }

    /**
     * Reads a registered client.
     *
     * @param clientId the client id
     * @return the client
     * @throws InvalidTokenException if the id is not a valid client id
     */
    public RegisteredClient readClient(final String clientId) {
        final JWTClaimsSet claims = decrypt(clientId, CLIENT, false);
        return RegisteredClient.of(string(claims, "name"), stringList(claims, "uris"));
    }

    /**
     * Issues the state token carried through the Google login and the consent page.
     *
     * <p>Business rule: it is valid for 10 minutes.
     *
     * @param state the pending authorization request
     * @return the compact token
     */
    public String issueLoginState(final LoginState state) {
        final JWTClaimsSet.Builder claims = base(STATE, STATE_TTL);
        claims.claim("cid", state.clientId());
        claims.claim("redirect", state.redirectUri());
        claims.claim("challenge", state.codeChallenge());
        if (state.clientState() != null) {
            claims.claim("cstate", state.clientState());
        }
        if (state.email() != null) {
            claims.claim("email", state.email());
            claims.claim("hd", state.hostedDomain());
        }
        return encrypt(claims);
    }

    /**
     * Reads a login state token.
     *
     * @param token the compact token
     * @return the state
     * @throws InvalidTokenException if the token is invalid, of another type or expired
     */
    public LoginState readLoginState(final String token) {
        final JWTClaimsSet claims = decrypt(token, STATE, true);
        final LoginState state = LoginState.of(
                string(claims, "cid"), string(claims, "redirect"), optional(claims, "cstate"),
                string(claims, "challenge"));
        final String email = optional(claims, "email");
        return email != null ? state.withIdentity(email, string(claims, "hd")) : state;
    }

    /**
     * Issues an authorization code.
     *
     * <p>Business rule: it is valid for 60 seconds.
     *
     * @param code the code contents
     * @return the compact code
     */
    public String issueAuthorizationCode(final AuthorizationCode code) {
        final JWTClaimsSet.Builder claims = base(CODE, CODE_TTL);
        claims.claim("email", code.email());
        claims.claim("hd", code.hostedDomain());
        claims.claim("cid", code.clientId());
        claims.claim("redirect", code.redirectUri());
        claims.claim("challenge", code.codeChallenge());
        return encrypt(claims);
    }

    /**
     * Reads an authorization code.
     *
     * <p>Business rule: without shared state a code cannot be marked as used; its short lifetime and the PKCE
     * check limit replay.
     *
     * @param code the compact code
     * @return the code contents
     * @throws InvalidTokenException if the code is invalid, of another type or expired
     */
    public AuthorizationCode redeemAuthorizationCode(final String code) {
        final JWTClaimsSet claims = decrypt(code, CODE, true);
        return AuthorizationCode.of(
                string(claims, "email"), string(claims, "hd"), string(claims, "cid"), string(claims, "redirect"),
                string(claims, "challenge"));
    }

    /**
     * Issues an access token and a refresh token.
     *
     * <p>Business rule: the lifetimes come from {@code access-token-ttl} and {@code refresh-token-ttl}.
     *
     * @param email the user's email
     * @param hostedDomain the Google hosted domain verified at login, re-checked on refresh
     * @param clientId the client the tokens are issued to
     * @return the tokens
     */
    public IssuedTokens issueTokens(final String email, final String hostedDomain, final String clientId) {
        final JWTClaimsSet.Builder access = base(ACCESS, accessTtl);
        access.audience(issuer);
        access.claim("email", email);
        access.claim("cid", clientId);
        final JWTClaimsSet.Builder refresh = base(REFRESH, refreshTtl);
        refresh.claim("email", email);
        refresh.claim("hd", hostedDomain);
        refresh.claim("cid", clientId);
        return IssuedTokens.of(encrypt(access), encrypt(refresh), accessTtl.toSeconds());
    }

    @Override
    public String verifyAccessToken(final String token) {
        final JWTClaimsSet claims = decrypt(token, ACCESS, true);
        final List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(issuer)) {
            throw new InvalidTokenException("Token audience mismatch");
        }
        return string(claims, "email");
    }

    /**
     * Reads a refresh token.
     *
     * @param token the compact token
     * @return the user and client it was issued to
     * @throws InvalidTokenException if the token is invalid, of another type or expired
     */
    public RefreshGrant readRefreshToken(final String token) {
        final JWTClaimsSet claims = decrypt(token, REFRESH, true);
        return RefreshGrant.of(string(claims, "email"), string(claims, "hd"), string(claims, "cid"));
    }

    private JWTClaimsSet.Builder base(final String type, final Duration ttl) {
        final Instant now = clock.instant();
        final JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
        claims.claim(TYPE, type);
        claims.issuer(issuer);
        claims.issueTime(Date.from(now));
        if (ttl != null) {
            claims.expirationTime(Date.from(now.plus(ttl)));
        }
        return claims;
    }

    private String encrypt(final JWTClaimsSet.Builder claims) {
        if (key == null) {
            throw new IllegalStateException("sql-mcp.auth.signing-key is required to issue tokens");
        }
        try {
            final EncryptedJWT jwt = new EncryptedJWT(
                    new JWEHeader(JWEAlgorithm.DIR, EncryptionMethod.A256GCM), claims.build());
            jwt.encrypt(new DirectEncrypter(key));
            return jwt.serialize();
        } catch (final Exception e) {
            throw new IllegalStateException("Could not issue token", e);
        }
    }

    private JWTClaimsSet decrypt(final String token, final String expectedType, final boolean expires) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Missing token");
        }
        if (key == null) {
            throw new InvalidTokenException("No signing key configured");
        }
        final JWTClaimsSet claims;
        try {
            final EncryptedJWT jwt = EncryptedJWT.parse(token);
            jwt.decrypt(new DirectDecrypter(key));
            claims = jwt.getJWTClaimsSet();
        } catch (final Exception e) {
            throw new InvalidTokenException("Invalid token", e);
        }
        if (!expectedType.equals(claims.getClaim(TYPE))) {
            throw new InvalidTokenException("Unexpected token type");
        }
        if (!issuer.equals(claims.getIssuer())) {
            throw new InvalidTokenException("Token issuer mismatch");
        }
        if (expires) {
            final Date exp = claims.getExpirationTime();
            if (exp == null || !clock.instant().isBefore(exp.toInstant())) {
                throw new InvalidTokenException("Token expired");
            }
        }
        return claims;
    }

    private static String string(final JWTClaimsSet claims, final String name) {
        final String value = optional(claims, name);
        if (value == null) {
            throw new InvalidTokenException("Token is missing " + name);
        }
        return value;
    }

    private static String optional(final JWTClaimsSet claims, final String name) {
        final Object value = claims.getClaim(name);
        return value instanceof String text ? text : null;
    }

    private static List<String> stringList(final JWTClaimsSet claims, final String name) {
        try {
            final List<String> value = claims.getStringListClaim(name);
            if (value == null) {
                throw new InvalidTokenException("Token is missing " + name);
            }
            return value;
        } catch (final java.text.ParseException e) {
            throw new InvalidTokenException("Invalid claim " + name, e);
        }
    }

    private static byte[] sha256(final String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
