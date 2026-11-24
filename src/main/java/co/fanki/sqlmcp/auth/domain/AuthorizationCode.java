package co.fanki.sqlmcp.auth.domain;

import java.util.Objects;

/**
 * The contents of an OAuth authorization code.
 *
 * <p>Business rule: it binds the user to the client, the redirect URI and the PKCE challenge it was issued for.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class AuthorizationCode {

    private final String email;
    private final String clientId;
    private final String redirectUri;
    private final String codeChallenge;

    private AuthorizationCode(
            final String email,
            final String clientId,
            final String redirectUri,
            final String codeChallenge) {
        this.email = email;
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.codeChallenge = codeChallenge;
    }

    /**
     * Creates an authorization code payload.
     *
     * @param email the authenticated user's email
     * @param clientId the client the code was issued to
     * @param redirectUri the redirect URI of the request
     * @param codeChallenge the PKCE S256 challenge
     * @return the payload
     * @throws NullPointerException if an argument is null
     */
    public static AuthorizationCode of(
            final String email,
            final String clientId,
            final String redirectUri,
            final String codeChallenge) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(redirectUri, "redirectUri must not be null");
        Objects.requireNonNull(codeChallenge, "codeChallenge must not be null");
        return new AuthorizationCode(email, clientId, redirectUri, codeChallenge);
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

}
