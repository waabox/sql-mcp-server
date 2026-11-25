package co.fanki.sqlmcp.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The contents of an OAuth authorization code.
 *
 * <p>Business rule: it binds the user to the client, the redirect URI and the PKCE challenge it was issued for.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class AuthorizationCode {

    private static final Pattern PKCE_VALUE = Pattern.compile("[A-Za-z0-9\\-._~]{43,128}");

    private final String email;
    private final String hostedDomain;
    private final String clientId;
    private final String redirectUri;
    private final String codeChallenge;

    private AuthorizationCode(
            final String email,
            final String hostedDomain,
            final String clientId,
            final String redirectUri,
            final String codeChallenge) {
        this.email = email;
        this.hostedDomain = hostedDomain;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.codeChallenge = codeChallenge;
    }

    /**
     * Creates an authorization code payload.
     *
     * @param email the authenticated user's email
     * @param hostedDomain the Google hosted domain verified at login
     * @param clientId the client the code was issued to
     * @param redirectUri the redirect URI of the request
     * @param codeChallenge the PKCE S256 challenge
     * @return the payload
     * @throws NullPointerException if an argument is null
     */
    public static AuthorizationCode of(
            final String email,
            final String hostedDomain,
            final String clientId,
            final String redirectUri,
            final String codeChallenge) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(hostedDomain, "hostedDomain must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(redirectUri, "redirectUri must not be null");
        Objects.requireNonNull(codeChallenge, "codeChallenge must not be null");
        return new AuthorizationCode(email, hostedDomain, clientId, redirectUri, codeChallenge);
    }

    /**
     * Tells whether a PKCE code challenge or verifier has a valid format.
     *
     * <p>Business rule: RFC 7636 requires 43 to 128 unreserved characters ({@code A-Z a-z 0-9 - . _ ~}).
     *
     * @param value the code challenge or code verifier; may be null
     * @return true when it has a valid format
     */
    public static boolean isPkceValue(final String value) {
        return value != null && PKCE_VALUE.matcher(value).matches();
    }

    /**
     * Returns the user's email.
     *
     * @return the email
     */
    public String email() {
        return email;
    }

    /**
     * Returns the Google hosted domain verified at login.
     *
     * @return the hosted domain
     */
    public String hostedDomain() {
        return hostedDomain;
    }

    /**
     * Returns the client id.
     *
     * @return the client id
     */
    public String clientId() {
        return clientId;
    }

    /**
     * Returns the redirect URI.
     *
     * @return the redirect URI
     */
    public String redirectUri() {
        return redirectUri;
    }

    /**
     * Returns the PKCE challenge.
     *
     * @return the code challenge
     */
    public String codeChallenge() {
        return codeChallenge;
    }

    /**
     * Checks a PKCE code verifier against the challenge of this code.
     *
     * <p>Business rule: only the S256 method is supported, compared in constant time.
     *
     * @param verifier the code verifier presented at the token endpoint
     * @return true when its SHA-256 matches the code challenge
     */
    public boolean matchesVerifier(final String verifier) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            final String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                    codeChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

}
