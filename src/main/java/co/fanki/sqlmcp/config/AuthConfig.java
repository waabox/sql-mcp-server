package co.fanki.sqlmcp.config;

import co.fanki.sqlmcp.auth.domain.GroupResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Configuration of the authentication and access components.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Configuration
public class AuthConfig {

    /**
     * Creates the fallback group resolver.
     *
     * <p>Business rule: it resolves no groups, so a Google user is denied every connection. The Google
     * implementation, when enabled, is marked primary and replaces it.
     *
     * @return a resolver that returns an empty set
     */
    @Bean
    public GroupResolver emptyGroupResolver() {
        return email -> Set.of();
    }

}
