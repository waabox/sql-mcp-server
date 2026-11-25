package co.fanki.sqlmcp.auth.application;

import co.fanki.sqlmcp.auth.domain.AuthorizationCode;
import co.fanki.sqlmcp.auth.domain.GoogleIdentity;
import co.fanki.sqlmcp.auth.domain.GoogleLogin;
import co.fanki.sqlmcp.auth.domain.GoogleLoginEnabled;
import co.fanki.sqlmcp.auth.domain.GoogleLoginException;
import co.fanki.sqlmcp.auth.domain.InvalidTokenException;
import co.fanki.sqlmcp.auth.domain.IssuedTokens;
import co.fanki.sqlmcp.auth.domain.LoginState;
import co.fanki.sqlmcp.auth.domain.RefreshGrant;
import co.fanki.sqlmcp.auth.domain.RegisteredClient;
import co.fanki.sqlmcp.auth.domain.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The OAuth 2.1 authorization server endpoints that MCP clients use to log a person in with Google.
 *
 * <p>Business rules: registered only when Google login is enabled; every endpoint is public. A request whose
 * client or redirect URI cannot be validated is answered with an error page and never redirected. After the Google
 * login the person must approve the client on a consent page before a code is issued. PKCE with S256 is required.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@RestController
@Conditional(GoogleLoginEnabled.class)
@ConditionalOnProperty(name = "sql-mcp.transport", havingValue = "http", matchIfMissing = true)
public class OAuthController {

    private static final Logger LOG = LoggerFactory.getLogger(OAuthController.class);
    private static final int MAX_REDIRECT_URIS = 5;
    private static final int MAX_URI_LENGTH = 255;
    private static final int MAX_NAME_LENGTH = 100;

    private final TokenService tokens;
    private final GoogleLogin googleLogin;

    /**
     * Creates the controller.
     *
     * @param tokens issues and reads the stateless tokens
     * @param googleLogin delegates the login to Google
     */
    public OAuthController(final TokenService tokens, final GoogleLogin googleLogin) {
        this.tokens = tokens;
        this.googleLogin = googleLogin;
    }

    /**
     * Registers a client dynamically (RFC 7591).
     *
     * @param request the registration request with {@code redirect_uris} and an optional {@code client_name}
     * @return 201 with the client id, or 400 {@code invalid_redirect_uri} / {@code invalid_client_metadata}
     *     (more than 5 redirect URIs, a URI over 255 characters, or a client name over 100 characters)
     */
    @PostMapping(value = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody final Map<String, Object> request) {
        final Object uris = request.get("redirect_uris");
        if (!(uris instanceof List<?> list) || list.isEmpty() || list.size() > MAX_REDIRECT_URIS) {
            return error(HttpStatus.BAD_REQUEST, "invalid_redirect_uri");
        }
        final List<String> redirectUris = new ArrayList<>();
        for (final Object item : list) {
            if (!(item instanceof String uri) || uri.length() > MAX_URI_LENGTH
                    || !RegisteredClient.isAcceptableRedirectUri(uri)) {
                return error(HttpStatus.BAD_REQUEST, "invalid_redirect_uri");
            }
            redirectUris.add(uri);
        }
        final Object requestedName = request.get("client_name");
        if (requestedName != null && !(requestedName instanceof String)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata");
        }
        final String name = requestedName instanceof String text && !text.isBlank() ? text.strip()
                : "Unnamed client";
        if (name.length() > MAX_NAME_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "invalid_client_metadata");
        }
        final String clientId = tokens.registerClient(RegisteredClient.of(name, redirectUris));
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("client_name", name);
        body.put("redirect_uris", redirectUris);
        body.put("token_endpoint_auth_method", "none");
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    /**
     * Starts an authorization: validates the request and sends the person to Google.
     *
     * @param clientId the registered client id
     * @param redirectUri the redirect URI of the client
     * @param responseType must be {@code code}
     * @param codeChallenge the PKCE challenge
     * @param codeChallengeMethod must be {@code S256}
     * @param state the opaque client state returned unchanged
     * @return a 400 page when the client, the redirect URI, the response type or the PKCE parameters are invalid
     *     (the client is never redirected on an invalid request), otherwise a redirect to Google
     */
    @GetMapping("/oauth/authorize")
    public ResponseEntity<String> authorize(
            @RequestParam(name = "client_id", required = false) final String clientId,
            @RequestParam(name = "redirect_uri", required = false) final String redirectUri,
            @RequestParam(name = "response_type", required = false) final String responseType,
            @RequestParam(name = "code_challenge", required = false) final String codeChallenge,
            @RequestParam(name = "code_challenge_method", required = false) final String codeChallengeMethod,
            @RequestParam(name = "state", required = false) final String state) {
        if (clientId == null || redirectUri == null) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request", "client_id and redirect_uri are required.");
        }
        final RegisteredClient client;
        try {
            client = tokens.readClient(clientId);
        } catch (InvalidTokenException e) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request", "The client is not registered.");
        }
        if (!client.allows(redirectUri)) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request", "The redirect URI is not registered.");
        }
        if (!"code".equals(responseType)) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request", "Only response_type=code is supported.");
        }
        if (!AuthorizationCode.isPkceValue(codeChallenge) || !"S256".equals(codeChallengeMethod)) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request",
                    "A PKCE code_challenge with code_challenge_method=S256 is required.");
        }
        final String loginState = tokens.issueLoginState(LoginState.of(clientId, redirectUri, state, codeChallenge));
        return redirect(googleLogin.authorizationUrl(loginState));
    }

    /**
     * Receives the person back from Google and shows the consent page.
     *
     * @param code the Google authorization code
     * @param state the login state sent to Google
     * @param error the error Google reports, when the login failed
     * @return the consent page, 403 when the identity is not acceptable, 400 when the state is invalid
     */
    @GetMapping("/oauth/google/callback")
    public ResponseEntity<String> googleCallback(
            @RequestParam(name = "code", required = false) final String code,
            @RequestParam(name = "state", required = false) final String state,
            @RequestParam(name = "error", required = false) final String error) {
        final LoginState loginState;
        final RegisteredClient client;
        try {
            loginState = tokens.readLoginState(state == null ? "" : state);
            client = tokens.readClient(loginState.clientId());
        } catch (InvalidTokenException e) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request", "The login request is invalid or has expired.");
        }
        if (error != null || code == null) {
            return page(HttpStatus.FORBIDDEN, "Login failed", "The login with Google was not completed.");
        }
        final GoogleIdentity identity;
        try {
            identity = googleLogin.loginWithCode(code);
        } catch (GoogleLoginException e) {
            LOG.warn("Google login rejected: {}", e.getMessage());
            return page(HttpStatus.FORBIDDEN, "Login failed", "This Google account is not allowed to use this server.");
        }
        final String signedState = tokens.issueLoginState(
                loginState.withIdentity(identity.email(), identity.hostedDomain()));
        return consentPage(client, loginState.redirectUri(), identity.email(), signedState);
    }

    /**
     * Records the person's decision about the client.
     *
     * @param loginState the login state issued with the consent page
     * @param decision {@code approve} or {@code deny}
     * @return a redirect to the client with a code, or with {@code error=access_denied}
     */
    @PostMapping(value = "/oauth/consent", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> consent(
            @RequestParam(name = "login_state", required = false) final String loginState,
            @RequestParam(name = "decision", required = false) final String decision) {
        final LoginState state;
        try {
            state = tokens.readLoginState(loginState == null ? "" : loginState);
        } catch (InvalidTokenException e) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request", "The login request is invalid or has expired.");
        }
        if (state.email() == null) {
            return page(HttpStatus.BAD_REQUEST, "Invalid request", "The login request is invalid or has expired.");
        }
        if (!"approve".equals(decision)) {
            return redirect(state.redirectUri(), error("access_denied", state.clientState()));
        }
        final String code = tokens.issueAuthorizationCode(AuthorizationCode.of(
                state.email(), state.hostedDomain(), state.clientId(), state.redirectUri(), state.codeChallenge()));
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        if (state.clientState() != null) {
            params.put("state", state.clientState());
        }
        return redirect(state.redirectUri(), params);
    }

    /**
     * The token endpoint: exchanges an authorization code or a refresh token for new tokens.
     *
     * @param form the form fields of the request; a refresh grant must send the {@code client_id} the token was
     *     issued to, and the hosted domain verified at login must still be in {@code allowed-domains}
     * @return the tokens, or a 400 JSON error ({@code invalid_request}, {@code invalid_grant},
     *     {@code unsupported_grant_type})
     */
    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam final Map<String, String> form) {
        final String grantType = form.get("grant_type");
        if ("authorization_code".equals(grantType)) {
            return exchangeCode(form);
        }
        if ("refresh_token".equals(grantType)) {
            return refresh(form);
        }
        return error(HttpStatus.BAD_REQUEST, grantType == null ? "invalid_request" : "unsupported_grant_type");
    }

    private ResponseEntity<Map<String, Object>> exchangeCode(final Map<String, String> form) {
        final String code = form.get("code");
        final String redirectUri = form.get("redirect_uri");
        final String clientId = form.get("client_id");
        final String verifier = form.get("code_verifier");
        if (code == null || redirectUri == null || clientId == null || verifier == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        if (!AuthorizationCode.isPkceValue(verifier)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant");
        }
        final AuthorizationCode redeemed;
        try {
            redeemed = tokens.redeemAuthorizationCode(code);
        } catch (InvalidTokenException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant");
        }
        if (!redeemed.clientId().equals(clientId) || !redeemed.redirectUri().equals(redirectUri)
                || !redeemed.matchesVerifier(verifier)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant");
        }
        return tokenResponse(tokens.issueTokens(redeemed.email(), redeemed.hostedDomain(), redeemed.clientId()));
    }

    private ResponseEntity<Map<String, Object>> refresh(final Map<String, String> form) {
        final String refreshToken = form.get("refresh_token");
        final String clientId = form.get("client_id");
        if (refreshToken == null || clientId == null) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request");
        }
        final RefreshGrant grant;
        try {
            grant = tokens.readRefreshToken(refreshToken);
        } catch (InvalidTokenException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant");
        }
        if (!clientId.equals(grant.clientId()) || !googleLogin.allowsHostedDomain(grant.hostedDomain())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant");
        }
        return tokenResponse(tokens.issueTokens(grant.email(), grant.hostedDomain(), grant.clientId()));
    }

    private static ResponseEntity<Map<String, Object>> tokenResponse(final IssuedTokens issued) {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", issued.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", issued.expiresInSeconds());
        body.put("refresh_token", issued.refreshToken());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(final HttpStatus status, final String code) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("error", code));
    }

    private static Map<String, String> error(final String code, final String state) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("error", code);
        if (state != null) {
            params.put("state", state);
        }
        return params;
    }

    private static ResponseEntity<String> redirect(final String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static ResponseEntity<String> redirect(final String base, final Map<String, String> params) {
        final StringBuilder url = new StringBuilder(base);
        char separator = base.contains("?") ? '&' : '?';
        for (final Map.Entry<String, String> param : params.entrySet()) {
            url.append(separator).append(param.getKey()).append('=')
                    .append(URLEncoder.encode(param.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        return redirect(url.toString());
    }

    private static ResponseEntity<String> consentPage(
            final RegisteredClient client,
            final String redirectUri,
            final String email,
            final String signedState) {
        final String host = HtmlUtils.htmlEscape(URI.create(redirectUri).getHost());
        final String body = "<h1>Authorize access</h1>"
                + "<p><strong>" + HtmlUtils.htmlEscape(client.clientName()) + "</strong> (" + host
                + ") wants to use this SQL server on your behalf.</p>"
                + "<p>Signed in as " + HtmlUtils.htmlEscape(email) + ".</p>"
                + "<form method=\"post\" action=\"/oauth/consent\">"
                + "<input type=\"hidden\" name=\"login_state\" value=\"" + HtmlUtils.htmlEscape(signedState) + "\">"
                + "<button type=\"submit\" name=\"decision\" value=\"approve\">Approve</button> "
                + "<button type=\"submit\" name=\"decision\" value=\"deny\">Deny</button>"
                + "</form>";
        return html(HttpStatus.OK, "Authorize access", body);
    }

    private static ResponseEntity<String> page(final HttpStatus status, final String title, final String message) {
        return html(status, title, "<h1>" + HtmlUtils.htmlEscape(title) + "</h1><p>"
                + HtmlUtils.htmlEscape(message) + "</p>");
    }

    private static ResponseEntity<String> html(final HttpStatus status, final String title, final String body) {
        final String document = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>"
                + HtmlUtils.htmlEscape(title) + "</title></head><body>" + body + "</body></html>";
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", "frame-ancestors 'none'")
                .body(document);
    }

}
