package co.fanki.sqlmcp.auth.domain;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Authentication settings, bound from {@code sql-mcp.auth}.
 *
 * <p>Business rules: when Google login is enabled the application refuses to start unless the issuer, the signing
 * key (at least 32 bytes), the Google client id and secret, the service account key and the admin email are all
 * present; a missing value never relaxes authentication.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConfigurationProperties(prefix = "sql-mcp.auth")
public class AuthSettings implements InitializingBean {

    private static final int MIN_SIGNING_KEY_BYTES = 32;

    private String issuer = "";
    private String signingKey = "";
    private Duration accessTokenTtl = Duration.ofHours(1);
    private Duration refreshTokenTtl = Duration.ofDays(30);
    private final Google google = new Google();

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

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(final String issuer) {
        this.issuer = issuer != null ? issuer : "";
    }

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(final String signingKey) {
        this.signingKey = signingKey != null ? signingKey : "";
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(final Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }

    public void setRefreshTokenTtl(final Duration refreshTokenTtl) {
        this.refreshTokenTtl = refreshTokenTtl;
    }

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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(final String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(final String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(final List<String> allowedDomains) {
            this.allowedDomains = allowedDomains != null ? new ArrayList<>(allowedDomains) : new ArrayList<>();
        }

        public String getServiceAccountKey() {
            return serviceAccountKey;
        }

        public void setServiceAccountKey(final String serviceAccountKey) {
            this.serviceAccountKey = serviceAccountKey;
        }

        public String getAdminEmail() {
            return adminEmail;
        }

        public void setAdminEmail(final String adminEmail) {
            this.adminEmail = adminEmail;
        }

        public Duration getGroupsCacheTtl() {
            return groupsCacheTtl;
        }

        public void setGroupsCacheTtl(final Duration groupsCacheTtl) {
            this.groupsCacheTtl = groupsCacheTtl;
        }

        public String getAuthorizeUrl() {
            return authorizeUrl;
        }

        public void setAuthorizeUrl(final String authorizeUrl) {
            this.authorizeUrl = authorizeUrl;
        }

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(final String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getDirectoryUrl() {
            return directoryUrl;
        }

        public void setDirectoryUrl(final String directoryUrl) {
            this.directoryUrl = directoryUrl;
        }

    }

}
