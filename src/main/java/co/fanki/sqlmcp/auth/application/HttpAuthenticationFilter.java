package co.fanki.sqlmcp.auth.application;

import co.fanki.sqlmcp.auth.domain.AccessTokenVerifier;
import co.fanki.sqlmcp.auth.domain.AuthSettings;
import co.fanki.sqlmcp.auth.domain.Caller;
import co.fanki.sqlmcp.auth.domain.InvalidTokenException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Identifies the caller of every HTTP request and rejects the ones that cannot be identified.
 *
 * <p>Business rules: {@code /health} and the OAuth and metadata endpoints this server publishes are public, matched
 * exactly on the request path (a path that merely starts like one of them, or that contains dot segments, is not
 * public). On every other path: with no shared token configured and Google login disabled the server is open and
 * the caller is anonymous (a warning is logged at startup); a bearer equal to the shared token identifies the shared-token
 * caller; when Google login is enabled a valid access token identifies the Google user; anything else is
 * rejected with 401 and a {@code WWW-Authenticate} header, which points at the protected resource metadata when
 * Google login is enabled so MCP clients can discover the login flow. The identified caller is stored in the
 * request attribute {@value #CALLER_ATTRIBUTE}.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http", matchIfMissing = true)
public class HttpAuthenticationFilter extends OncePerRequestFilter {

    /** The request attribute that holds the {@link Caller}. */
    public static final String CALLER_ATTRIBUTE = "sqlmcp.caller";

    private static final Logger LOG = LoggerFactory.getLogger(HttpAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/health",
            "/.well-known/oauth-protected-resource",
            "/.well-known/oauth-authorization-server",
            "/oauth/register",
            "/oauth/authorize",
            "/oauth/google/callback",
            "/oauth/consent",
            "/oauth/token");

    private final byte[] sharedToken;
    private final boolean googleEnabled;
    private final String issuer;
    private final ObjectProvider<AccessTokenVerifier> tokenVerifier;

    /**
     * Creates the filter.
     *
     * @param authToken the configured shared token; blank disables it
     * @param settings the authentication settings
     * @param tokenVerifier verifies the access tokens issued by this server (only used when Google is enabled)
     */
    public HttpAuthenticationFilter(
            @Value("${sql-mcp.http.auth-token:}") final String authToken,
            final AuthSettings settings,
            final ObjectProvider<AccessTokenVerifier> tokenVerifier) {
        this.sharedToken = authToken == null || authToken.isBlank()
                ? null
                : authToken.strip().getBytes(StandardCharsets.UTF_8);
        this.googleEnabled = settings.googleEnabled();
        this.issuer = stripTrailingSlash(settings.getIssuer());
        this.tokenVerifier = tokenVerifier;
        if (sharedToken == null && !googleEnabled) {
            LOG.warn("HTTP transport is running WITHOUT authentication. Anyone who can reach this server "
                    + "can query every configured database. Set sql-mcp.http.auth-token to require a bearer token.");
        }
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {
        if (sharedToken == null && !googleEnabled) {
            request.setAttribute(CALLER_ATTRIBUTE, Caller.anonymous());
            filterChain.doFilter(request, response);
            return;
        }
        final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            final String provided = header.substring(BEARER_PREFIX.length()).strip();
            final Caller caller = identify(provided);
            if (caller != null) {
                request.setAttribute(CALLER_ATTRIBUTE, caller);
                filterChain.doFilter(request, response);
                return;
            }
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, googleEnabled
                ? "Bearer resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource\""
                : "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

    private Caller identify(final String provided) {
        if (sharedToken != null
                && MessageDigest.isEqual(sharedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            return Caller.sharedToken();
        }
        if (googleEnabled) {
            final AccessTokenVerifier verifier = tokenVerifier.getIfAvailable();
            if (verifier != null) {
                try {
                    return Caller.googleUser(verifier.verifyAccessToken(provided));
                } catch (InvalidTokenException e) {
                    LOG.debug("Rejected access token: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    private static String stripTrailingSlash(final String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

}
