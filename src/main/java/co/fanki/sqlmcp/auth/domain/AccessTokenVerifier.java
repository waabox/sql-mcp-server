package co.fanki.sqlmcp.auth.domain;

/**
 * Verifies the access tokens issued by this server.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public interface AccessTokenVerifier {

    /**
     * Verifies an access token.
     *
     * <p>Business rule: only an unexpired access token issued by this server is accepted; any other token type
     * is rejected.
     *
     * @param token the compact token
     * @return the email of the user the token was issued to
     * @throws InvalidTokenException if the token is not a valid access token
     */
    String verifyAccessToken(String token);

}
