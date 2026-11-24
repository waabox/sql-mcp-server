package co.fanki.sqlmcp.auth.domain;

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

}
