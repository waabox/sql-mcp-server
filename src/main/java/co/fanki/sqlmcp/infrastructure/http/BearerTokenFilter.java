package co.fanki.sqlmcp.infrastructure.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Requires a bearer token on every HTTP request when the HTTP transport is enabled.
 *
 * <p>Business rules: when {@code sql-mcp.http.auth-token} is set, every request
 * except {@code /health} must carry {@code Authorization: Bearer <token>};
 * otherwise it is rejected with 401. When no token is configured the server
 * stays open and logs a warning at startup, so existing deployments keep working.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http", matchIfMissing = true)
public class BearerTokenFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEALTH_PATH = "/health";

    private final byte[] expectedToken;

    /**
     * Creates the filter.
     *
     * @param authToken the configured token; blank disables authentication
     */
    public BearerTokenFilter(@Value("${sql-mcp.http.auth-token:}") final String authToken) {
        if (authToken == null || authToken.isBlank()) {
            this.expectedToken = null;
            LOG.warn("HTTP transport is running WITHOUT authentication. Anyone who can reach this server "
                    + "can query every configured database. Set sql-mcp.http.auth-token to require a bearer token.");
        } else {
            this.expectedToken = authToken.strip().getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        return expectedToken == null || HEALTH_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            byte[] provided = header.substring(BEARER_PREFIX.length()).strip().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expectedToken, provided)) {
                filterChain.doFilter(request, response);
                return;
            }
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }

}
