package co.fanki.sqlmcp.auth.domain;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * An OAuth client that registered itself dynamically.
 *
 * <p>Business rule: it is immutable; its identity is carried by the client id issued by {@link TokenService}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class RegisteredClient {

    private final String clientName;
    private final List<String> redirectUris;

    private RegisteredClient(final String clientName, final List<String> redirectUris) {
        this.clientName = clientName;
        this.redirectUris = redirectUris;
    }

    /**
     * Creates a registered client.
     *
     * @param clientName the display name shown on the consent page
     * @param redirectUris the registered redirect URIs
     * @return the client
     * @throws NullPointerException if an argument is null
     */
    public static RegisteredClient of(final String clientName, final List<String> redirectUris) {
        Objects.requireNonNull(clientName, "clientName must not be null");
        Objects.requireNonNull(redirectUris, "redirectUris must not be null");
        return new RegisteredClient(clientName, List.copyOf(redirectUris));
    }

    /**
     * Returns the display name.
     *
     * @return the client name
     */
    public String clientName() {
        return clientName;
    }

    /**
     * Returns the registered redirect URIs.
     *
     * @return an immutable list
     */
    public List<String> redirectUris() {
        return redirectUris;
    }

    /**
     * Tells whether a redirect URI may be registered.
     *
     * <p>Business rule: only {@code https://} URIs and {@code http://} loopback URIs ({@code localhost},
     * {@code 127.0.0.1}) without a fragment are accepted.
     *
     * @param uri the redirect URI
     * @return true when it may be registered
     */
    public static boolean isAcceptableRedirectUri(final String uri) {
        final URI parsed = parse(uri);
        if (parsed == null || parsed.getHost() == null || parsed.getRawFragment() != null) {
            return false;
        }
        return "https".equals(parsed.getScheme()) || isLoopback(parsed);
    }

    /**
     * Tells whether a requested redirect URI matches one of the registered ones.
     *
     * <p>Business rule: the match is exact, except that a loopback URI may use a different port than the
     * registered one (RFC 8252).
     *
     * @param requested the redirect URI of the authorization request
     * @return true when it is allowed
     */
    public boolean allows(final String requested) {
        final URI wanted = parse(requested);
        for (final String registered : redirectUris) {
            if (registered.equals(requested)) {
                return true;
            }
            final URI known = parse(registered);
            if (wanted != null && known != null && isLoopback(known) && isLoopback(wanted)
                    && Objects.equals(known.getHost(), wanted.getHost())
                    && Objects.equals(known.getRawPath(), wanted.getRawPath())
                    && Objects.equals(known.getRawQuery(), wanted.getRawQuery())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLoopback(final URI uri) {
        return "http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
    }

    private static URI parse(final String uri) {
        try {
            return URI.create(uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
