package co.fanki.sqlmcp.auth.domain;

/**
 * The contents of a valid refresh token.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class RefreshGrant {

    private final String email;
    private final String clientId;

    private RefreshGrant(final String email, final String clientId) {
        this.email = email;
        this.clientId = clientId;
    }

    /**
     * Creates the grant.
     *
     * @param email the user's email
     * @param clientId the client the token was issued to
     * @return the grant
     */
    public static RefreshGrant of(final String email, final String clientId) {
        return new RefreshGrant(email, clientId);
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

}
