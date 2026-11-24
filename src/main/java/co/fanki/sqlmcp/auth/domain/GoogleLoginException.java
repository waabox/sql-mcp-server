package co.fanki.sqlmcp.auth.domain;

/**
 * Thrown when a Google login cannot be completed or its identity is not acceptable.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class GoogleLoginException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the reason the login was rejected
     */
    public GoogleLoginException(final String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the reason the login was rejected
     * @param cause the underlying failure
     */
    public GoogleLoginException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
