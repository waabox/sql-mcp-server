package co.fanki.sqlmcp.auth.domain;

/**
 * Thrown when the groups of a user cannot be resolved (for example Google is unreachable).
 *
 * <p>Business rule: callers must treat it as "no access", never as "access".
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public class GroupResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the reason
     */
    public GroupResolutionException(final String message) {
        super(message);
    }

    /**
     * Creates the exception.
     *
     * @param message the reason
     * @param cause the underlying cause
     */
    public GroupResolutionException(final String message, final Throwable cause) {
        super(message, cause);
    }

}
