package co.fanki.sqlmcp.auth.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * The party on whose behalf a tool call runs.
 *
 * <p>Business rules: a caller is either the local STDIO user, an anonymous HTTP caller (server running open),
 * a caller authenticated with the shared token, or a Google user identified by a verified email.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class Caller {

    /** The kinds of caller. */
    public enum Kind {
        /** The local user of the STDIO transport. */
        LOCAL,
        /** An HTTP caller on a server with no authentication configured. */
        ANONYMOUS,
        /** An HTTP caller that presented the shared token. */
        SHARED_TOKEN,
        /** An HTTP caller authenticated with Google. */
        GOOGLE_USER
    }

    private final Kind kind;
    private final String email;

    private Caller(final Kind kind, final String email) {
        this.kind = kind;
        this.email = email;
    }

    /**
     * Creates the caller of the STDIO transport.
     *
     * @return the local caller
     */
    public static Caller local() {
        return new Caller(Kind.LOCAL, null);
    }

    /**
     * Creates an anonymous caller (HTTP server running without authentication).
     *
     * @return the anonymous caller
     */
    public static Caller anonymous() {
        return new Caller(Kind.ANONYMOUS, null);
    }

    /**
     * Creates a caller authenticated with the shared token.
     *
     * @return the shared token caller
     */
    public static Caller sharedToken() {
        return new Caller(Kind.SHARED_TOKEN, null);
    }

    /**
     * Creates a Google user caller.
     *
     * @param email the verified email of the user; must not be blank
     * @return the Google user caller
     * @throws NullPointerException if the email is null
     * @throws IllegalArgumentException if the email is blank
     */
    public static Caller googleUser(final String email) {
        Objects.requireNonNull(email, "email must not be null");
        if (email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        return new Caller(Kind.GOOGLE_USER, email.strip());
    }

    /**
     * Returns the kind of caller.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the email of a Google user.
     *
     * @return the email, empty for every other kind of caller
     */
    public Optional<String> email() {
        return Optional.ofNullable(email);
    }

    /**
     * Returns the identity recorded in the audit log.
     *
     * @return {@code local}, {@code anonymous}, {@code shared-token}, or the user's email
     */
    public String auditName() {
        return switch (kind) {
            case LOCAL -> "local";
            case ANONYMOUS -> "anonymous";
            case SHARED_TOKEN -> "shared-token";
            case GOOGLE_USER -> email;
        };
    }

}
