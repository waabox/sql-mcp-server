package co.fanki.sqlmcp.auth.domain;

/**
 * Thrown when a token is malformed, expired, of the wrong type or not issued by this server.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class InvalidTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the reason
     */
    public InvalidTokenException(final String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the reason
     * @param cause the underlying cause
     */
    public InvalidTokenException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
