package co.fanki.sqlmcp.auth.domain;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Checks at startup that the Google Directory settings exist when the access policy maps Google groups.
 *
 * <p>Business rules: when Google login is enabled and at least one access entry uses a group, the service account
 * key and the admin email are required; with users-only entries (or Google login disabled) they may be empty. A
 * missing value stops startup naming the key.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
public class GroupAccessSettingsValidator implements InitializingBean {

    private final AuthSettings settings;
    private final AccessPolicy accessPolicy;

    /**
     * Creates the validator.
     *
     * @param settings the authentication settings
     * @param accessPolicy the access policy
     */
    public GroupAccessSettingsValidator(final AuthSettings settings, final AccessPolicy accessPolicy) {
        this.settings = Objects.requireNonNull(settings);
        this.accessPolicy = Objects.requireNonNull(accessPolicy);
    }

    /**
     * Validates once Spring has built the beans, so an invalid configuration prevents startup.
     *
     * @throws IllegalStateException if the settings are invalid (see {@link #validate()})
     */
    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * Checks the Directory settings against the policy.
     *
     * @throws IllegalStateException naming the missing key when a group entry exists without it
     */
    public void validate() {
        if (!settings.googleEnabled() || !accessPolicy.usesGroups()) {
            return;
        }
        AuthSettings.require(settings.getGoogle().getServiceAccountKey(),
                "sql-mcp.auth.google.service-account-key (needed because sql-mcp.access maps a group)");
        AuthSettings.require(settings.getGoogle().getAdminEmail(),
                "sql-mcp.auth.google.admin-email (needed because sql-mcp.access maps a group)");
    }

}
