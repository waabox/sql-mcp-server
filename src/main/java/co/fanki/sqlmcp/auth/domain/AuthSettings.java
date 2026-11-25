package co.fanki.sqlmcp.auth.domain;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Authentication settings, bound from {@code sql-mcp.auth}.
 *
 * <p>Business rules: when Google login is enabled the application refuses to start unless the issuer, the signing
 * key (at least 32 bytes), the Google client id and secret, the service account key and the admin email are all
 * present; a missing value never relaxes authentication. The issuer must be an absolute URL without path, query or
 * fragment, and must use https unless the host is loopback.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp.auth")
public class AuthSettings implements InitializingBean {

    private static final int MIN_SIGNING_KEY_BYTES = 32;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private String issuer = "";
    private String signingKey = "";
    private Duration accessTokenTtl = Duration.ofHours(1);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private final Google google = new Google();

    /**
     * Validates the settings once Spring has bound them, so an invalid configuration prevents startup.
     *
     * @throws IllegalStateException if the settings are invalid (see {@link #validate()})
     */
    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * Validates the settings.
     *
     * @throws IllegalStateException naming the missing or invalid key when Google login is enabled
     */
    public void validate() {
        if (!google.enabled) {
            return;
        }
        require(issuer, "sql-mcp.auth.issuer");
        validateIssuer();
        require(signingKey, "sql-mcp.auth.signing-key");
        if (signingKey.getBytes(StandardCharsets.UTF_8).length < MIN_SIGNING_KEY_BYTES) {
            throw new IllegalStateException(
                    "sql-mcp.auth.signing-key must be at least " + MIN_SIGNING_KEY_BYTES + " bytes");
        }
        require(google.clientId, "sql-mcp.auth.google.client-id");
        require(google.clientSecret, "sql-mcp.auth.google.client-secret");
        require(google.serviceAccountKey, "sql-mcp.auth.google.service-account-key");
        require(google.adminEmail, "sql-mcp.auth.google.admin-email");
    }

    private void validateIssuer() {
        final URI uri;
        try {
            uri = new URI(issuer.strip());
        } catch (URISyntaxException e) {
            throw invalidIssuer("is not a valid URL");
        }
        final String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw invalidIssuer("must be an absolute http or https URL");
        }
        final String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw invalidIssuer("must have a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalidIssuer("must not contain user info");
        }
        final String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw invalidIssuer("must not have a path (the endpoints are served from the root)");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw invalidIssuer("must not have a query or a fragment");
        }
        if ("http".equals(scheme) && !LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw invalidIssuer("must use https unless the host is loopback (localhost, 127.0.0.1)");
        }
    }

    private static IllegalStateException invalidIssuer(final String reason) {
        return new IllegalStateException("sql-mcp.auth.issuer " + reason);
    }

    private static void require(final String value, final String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Google login is enabled but " + key + " is not set");
        }
    }

    /**
     * Tells whether Google login is enabled.
     *
     * @return true when enabled
     */
    public boolean googleEnabled() {
        return google.enabled;
    }

    /**
     * Returns the issuer.
     *
     * @return the issuer
     */
    public String getIssuer() {
        return issuer;
    }

    /**
     * Sets the issuer; called by Spring when binding the configuration.
     *
     * @param issuer the issuer
     */
    public void setIssuer(final String issuer) {
        this.issuer = issuer != null ? issuer : "";
    }

    /**
     * Returns the signing key.
     *
     * @return the signing key
     */
    public String getSigningKey() {
        return signingKey;
    }

    /**
     * Sets the signing key; called by Spring when binding the configuration.
     *
     * @param signingKey the signing key
     */
    public void setSigningKey(final String signingKey) {
        this.signingKey = signingKey != null ? signingKey : "";
    }

    /**
     * Returns the access token ttl.
     *
     * @return the access token ttl
     */
    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    /**
     * Sets the access token ttl; called by Spring when binding the configuration.
     *
     * @param accessTokenTtl the access token ttl
     */
    public void setAccessTokenTtl(final Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    /**
     * Returns the refresh token ttl.
     *
     * @return the refresh token ttl
     */
    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    /**
     * Sets the refresh token ttl; called by Spring when binding the configuration.
     *
     * @param refreshTokenTtl the refresh token ttl
     */
    public void setRefreshTokenTtl(final Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /**
     * Returns the Google login settings.
     *
     * @return the Google settings
     */
    public Google getGoogle() {
        return google;
    }

    /** Google login settings, bound from {@code sql-mcp.auth.google}. */
    public static class Google {

        private boolean enabled;
        private String clientId = "";
        private String clientSecret = "";
        private List<String> allowedDomains = new ArrayList<>();
        private String serviceAccountKey = "";
        private String adminEmail = "";
        private Duration groupsCacheTtl = Duration.ofMinutes(5);
        private String authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth";
        private String tokenUrl = "https://oauth2.googleapis.com/token";
        private String directoryUrl = "https://admin.googleapis.com";

        /**
         * Returns the enabled.
         *
         * @return the enabled
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets the enabled; called by Spring when binding the configuration.
         *
         * @param enabled the enabled
         */
        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns the client id.
         *
         * @return the client id
         */
        public String getClientId() {
            return clientId;
        }

        /**
         * Sets the client id; called by Spring when binding the configuration.
         *
         * @param clientId the client id
         */
        public void setClientId(final String clientId) {
            this.clientId = clientId;
        }

        /**
         * Returns the client secret.
         *
         * @return the client secret
         */
        public String getClientSecret() {
            return clientSecret;
        }

        /**
         * Sets the client secret; called by Spring when binding the configuration.
         *
         * @param clientSecret the client secret
         */
        public void setClientSecret(final String clientSecret) {
            this.clientSecret = clientSecret;
        }

        /**
         * Returns the allowed domains.
         *
         * @return the allowed domains
         */
        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        /**
         * Sets the allowed domains; called by Spring when binding the configuration.
         *
         * @param allowedDomains the allowed domains
         */
        public void setAllowedDomains(final List<String> allowedDomains) {
            this.allowedDomains = allowedDomains != null ? new ArrayList<>(allowedDomains) : new ArrayList<>();
        }

        /**
         * Returns the service account key.
         *
         * @return the service account key
         */
        public String getServiceAccountKey() {
            return serviceAccountKey;
        }

        /**
         * Sets the service account key; called by Spring when binding the configuration.
         *
         * @param serviceAccountKey the service account key
         */
        public void setServiceAccountKey(final String serviceAccountKey) {
            this.serviceAccountKey = serviceAccountKey;
        }

        /**
         * Returns the admin email.
         *
         * @return the admin email
         */
        public String getAdminEmail() {
            return adminEmail;
        }

        /**
         * Sets the admin email; called by Spring when binding the configuration.
         *
         * @param adminEmail the admin email
         */
        public void setAdminEmail(final String adminEmail) {
            this.adminEmail = adminEmail;
        }

        /**
         * Returns the groups cache ttl.
         *
         * @return the groups cache ttl
         */
        public Duration getGroupsCacheTtl() {
            return groupsCacheTtl;
        }

        /**
         * Sets the groups cache ttl; called by Spring when binding the configuration.
         *
         * @param groupsCacheTtl the groups cache ttl
         */
        public void setGroupsCacheTtl(final Duration groupsCacheTtl) {
            this.groupsCacheTtl = groupsCacheTtl;
        }

        /**
         * Returns the authorize url.
         *
         * @return the authorize url
         */
        public String getAuthorizeUrl() {
            return authorizeUrl;
        }

        /**
         * Sets the authorize url; called by Spring when binding the configuration.
         *
         * @param authorizeUrl the authorize url
         */
        public void setAuthorizeUrl(final String authorizeUrl) {
            this.authorizeUrl = authorizeUrl;
        }

        /**
         * Returns the token url.
         *
         * @return the token url
         */
        public String getTokenUrl() {
            return tokenUrl;
        }

        /**
         * Sets the token url; called by Spring when binding the configuration.
         *
         * @param tokenUrl the token url
         */
        public void setTokenUrl(final String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        /**
         * Returns the directory url.
         *
         * @return the directory url
         */
        public String getDirectoryUrl() {
            return directoryUrl;
        }

        /**
         * Sets the directory url; called by Spring when binding the configuration.
         *
         * @param directoryUrl the directory url
         */
        public void setDirectoryUrl(final String directoryUrl) {
            this.directoryUrl = directoryUrl;
        }

    }

}
