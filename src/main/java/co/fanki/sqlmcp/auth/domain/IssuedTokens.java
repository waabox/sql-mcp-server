package co.fanki.sqlmcp.auth.domain;

/**
 * The access and refresh tokens issued to a client.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class IssuedTokens {

    private final String accessToken;
    private final String refreshToken;
    private final long expiresInSeconds;

    private IssuedTokens(final String accessToken, final String refreshToken, final long expiresInSeconds) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresInSeconds = expiresInSeconds;
    }

    /**
     * Creates the pair.
     *
     * @param accessToken the access token
     * @param refreshToken the refresh token
     * @param expiresInSeconds the access token lifetime in seconds
     * @return the issued tokens
     */
    public static IssuedTokens of(final String accessToken, final String refreshToken, final long expiresInSeconds) {
        return new IssuedTokens(accessToken, refreshToken, expiresInSeconds);
    }

    /**
     * Returns the access token.
     *
     * @return the compact token
     */
    public String accessToken() {
        return accessToken;
    }

    /**
     * Returns the refresh token.
     *
     * @return the compact token
     */
    public String refreshToken() {
        return refreshToken;
    }

    /**
     * Returns the access token lifetime.
     *
     * @return seconds until the access token expires
     */
    public long expiresInSeconds() {
        return expiresInSeconds;
    }

}
