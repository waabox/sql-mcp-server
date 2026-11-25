package co.fanki.sqlmcp.auth.domain;

import java.util.Objects;

/**
 * An identity verified by Google: the user's email and the hosted domain (the {@code hd} claim) it belongs to.
 *
 * <p>Business rule: the hosted domain, not the domain of the email, is what is checked against
 * {@code allowed-domains}, at login and again on every refresh.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
public final class GoogleIdentity {

    private final String email;
    private final String hostedDomain;

    private GoogleIdentity(final String email, final String hostedDomain) {
        this.email = email;
        this.hostedDomain = hostedDomain;
    }

    /**
     * Creates the identity.
     *
     * @param email the verified, lower-cased email
     * @param hostedDomain the verified, lower-cased hosted domain
     * @return the identity
     * @throws NullPointerException if an argument is null
     */
    public static GoogleIdentity of(final String email, final String hostedDomain) {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(hostedDomain, "hostedDomain must not be null");
        return new GoogleIdentity(email, hostedDomain);
    }

    /**
     * Returns the email.
     *
     * @return the verified, lower-cased email
     */
    public String email() {
        return email;
    }

    /**
     * Returns the hosted domain.
     *
     * @return the verified, lower-cased hosted domain
     */
    public String hostedDomain() {
        return hostedDomain;
    }

}
