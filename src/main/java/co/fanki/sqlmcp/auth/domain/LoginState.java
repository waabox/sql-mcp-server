package co.fanki.sqlmcp.auth.domain;

import java.util.Objects;

/**
 * The state carried through the Google login round trip and the consent page.
 *
 * <p>Business rule: it holds the pending authorization request; the email is only set once Google has authenticated
 * the user.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class LoginState {

    private final String clientId;
    private final String redirectUri;
    private final String clientState;
    private final String codeChallenge;
    private final String email;
    private final String hostedDomain;

    private LoginState(
            final String clientId,
            final String redirectUri,
            final String clientState,
            final String codeChallenge,
            final String email,
            final String hostedDomain) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.clientState = clientState;
        this.codeChallenge = codeChallenge;
        this.email = email;
        this.hostedDomain = hostedDomain;
    }

    /**
     * Creates the state of a pending authorization request, before the user logged in.
     *
     * @param clientId the requesting client
     * @param redirectUri the validated redirect URI
     * @param clientState the client's opaque {@code state}, may be null
     * @param codeChallenge the PKCE S256 challenge
     * @return the state without an email
     * @throws NullPointerException if clientId, redirectUri or codeChallenge is null
     */
    public static LoginState of(
            final String clientId,
            final String redirectUri,
            final String clientState,
            final String codeChallenge) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(redirectUri, "redirectUri must not be null");
        Objects.requireNonNull(codeChallenge, "codeChallenge must not be null");
        return new LoginState(clientId, redirectUri, clientState, codeChallenge, null, null);
    }

    /**
     * Returns a copy of this state with the verified identity of the user.
     *
     * @param verifiedEmail the email Google authenticated
     * @param verifiedHostedDomain the hosted domain Google reported
     * @return the new state
     * @throws NullPointerException if an argument is null
     */
    public LoginState withIdentity(final String verifiedEmail, final String verifiedHostedDomain) {
        Objects.requireNonNull(verifiedEmail, "verifiedEmail must not be null");
        Objects.requireNonNull(verifiedHostedDomain, "verifiedHostedDomain must not be null");
        return new LoginState(clientId, redirectUri, clientState, codeChallenge, verifiedEmail, verifiedHostedDomain);
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
     * Returns the client's opaque state.
     *
     * @return the state, or null when the client sent none
     */
    public String clientState() {
        return clientState;
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
     * Returns the verified email.
     *
     * @return the email, or null before the Google login completed
     */
    public String email() {
        return email;
    }

    /**
     * Returns the verified hosted domain.
     *
     * @return the hosted domain, or null before the Google login completed
     */
    public String hostedDomain() {
        return hostedDomain;
    }

}
