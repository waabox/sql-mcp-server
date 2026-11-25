package co.fanki.sqlmcp.auth.domain;

/**
 * The contents of a valid refresh token.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class RefreshGrant {

    private final String email;
    private final String hostedDomain;
    private final String clientId;

    private RefreshGrant(final String email, final String hostedDomain, final String clientId) {
        this.email = email;
        this.hostedDomain = hostedDomain;
        this.clientId = clientId;
    }

    /**
     * Creates the grant.
     *
     * @param email the user's email
     * @param hostedDomain the Google hosted domain verified at login
     * @param clientId the client the token was issued to
     * @return the grant
     */
    public static RefreshGrant of(final String email, final String hostedDomain, final String clientId) {
        return new RefreshGrant(email, hostedDomain, clientId);
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

}
