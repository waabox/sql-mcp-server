package co.fanki.sqlmcp.auth.application;

import co.fanki.sqlmcp.auth.domain.AuthSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the OAuth metadata MCP clients use to discover the login flow.
 *
 * <p>Business rule: registered only when Google login is enabled; the endpoints are public.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
@ConditionalOnExpression("'${sql-mcp.auth.google.enabled:false}' == 'true'"
        + " and '${sql-mcp.transport:http}' == 'http'")
public class OAuthMetadataController {

    private final String issuer;

    /**
     * Creates the controller.
     *
     * @param settings the authentication settings (issuer)
     */
    public OAuthMetadataController(final AuthSettings settings) {
        this.issuer = settings.getIssuer().replaceAll("/+$", "");
    }

    /**
     * Returns the protected resource metadata.
     *
     * @return the resource and its authorization server
     */
    @GetMapping("/.well-known/oauth-protected-resource")
    public Map<String, Object> protectedResource() {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", issuer + "/mcp");
        body.put("authorization_servers", List.of(issuer));
        return body;
    }

    /**
     * Returns the authorization server metadata.
     *
     * @return the endpoints and capabilities of this authorization server
     */
    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> authorizationServer() {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("authorization_endpoint", issuer + "/oauth/authorize");
        body.put("token_endpoint", issuer + "/oauth/token");
        body.put("registration_endpoint", issuer + "/oauth/register");
        body.put("response_types_supported", List.of("code"));
        body.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        return body;
    }

}
