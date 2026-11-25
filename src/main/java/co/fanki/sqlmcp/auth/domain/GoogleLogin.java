package co.fanki.sqlmcp.auth.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Delegates the login to Google: builds the redirect, exchanges the code and validates the identity.
 *
 * <p>Business rules: the ID token comes straight from Google's token endpoint over TLS, so its signature is not
 * verified again, but its claims are: the issuer must be Google, the audience this server's Google client id, the
 * token must not be expired, {@code email_verified} must be true and the {@code hd} (hosted domain) claim must be
 * present and in {@code allowed-domains} (case-insensitive). Checking only the email's domain is not enough: a
 * personal Google account can be created on a company address. An empty {@code allowed-domains} rejects every
 * login.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@Conditional(GoogleLoginEnabled.class)
public class GoogleLogin {

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final List<String> allowedDomains;
    private final String authorizeUrl;
    private final String tokenUrl;
    private final Clock clock;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Creates the login helper.
     *
     * @param settings the authentication settings (Google client, allowed domains, endpoints)
     * @param clock the clock used to check the ID token expiry
     */
    public GoogleLogin(final AuthSettings settings, final Clock clock) {
        final AuthSettings.Google google = settings.getGoogle();
        this.issuer = settings.getIssuer().replaceAll("/+$", "");
        this.clientId = google.getClientId();
        this.clientSecret = google.getClientSecret();
        this.allowedDomains = google.getAllowedDomains().stream()
                .map(domain -> domain.strip().toLowerCase(Locale.ROOT))
                .toList();
        this.authorizeUrl = google.getAuthorizeUrl();
        this.tokenUrl = google.getTokenUrl();
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * Tells whether the domain of an email is in {@code allowed-domains}.
     *
     * <p>Business rule: used to re-check a returning user (refresh grant); the comparison is case-insensitive and an
     * empty {@code allowed-domains} allows nobody.
     *
     * @param email the email
     * @return true when the domain of the email is allowed
     */
    public boolean allowsEmailDomain(final String email) {
        if (email == null) {
            return false;
        }
        final int at = email.lastIndexOf('@');
        if (at < 0) {
            return false;
        }
        return allowedDomains.contains(email.substring(at + 1).strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Builds the URL that sends the user to Google to log in.
     *
     * <p>Business rule: when exactly one domain is allowed it is passed as the {@code hd} hint.
     *
     * @param state the signed login state returned unchanged by Google
     * @return the Google authorization URL
     */
    public String authorizationUrl(final String state) {
        final StringBuilder url = new StringBuilder(authorizeUrl);
        url.append(authorizeUrl.contains("?") ? '&' : '?');
        url.append("client_id=").append(encode(clientId));
        url.append("&redirect_uri=").append(encode(callbackUri()));
        url.append("&response_type=code&scope=").append(encode("openid email"));
        url.append("&state=").append(encode(state));
        if (allowedDomains.size() == 1) {
            url.append("&hd=").append(encode(allowedDomains.get(0)));
        }
        return url.toString();
    }

    /**
     * Returns the callback URI registered with Google.
     *
     * @return the issuer followed by {@code /oauth/google/callback}
     */
    public String callbackUri() {
        return issuer + "/oauth/google/callback";
    }

    /**
     * Exchanges the authorization code from Google and validates the identity it carries.
     *
     * @param code the authorization code received from Google
     * @return the verified, lower-cased email
     * @throws GoogleLoginException if Google cannot be reached, answers an error or the identity is not acceptable
     */
    public String loginWithCode(final String code) {
        final String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&redirect_uri=" + encode(callbackUri());
        final HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(HTTP_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new GoogleLoginException("Google token endpoint answered " + response.statusCode());
            }
            final Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() { });
            final Object idToken = body.get("id_token");
            if (!(idToken instanceof String token)) {
                throw new GoogleLoginException("Google did not return an ID token");
            }
            return validateIdToken(claimsOf(token));
        } catch (IOException e) {
            throw new GoogleLoginException("Could not read the Google response", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleLoginException("Interrupted while contacting Google", e);
        }
    }

    /**
     * Validates the claims of a Google ID token.
     *
     * @param claims the ID token claims
     * @return the verified, lower-cased email
     * @throws GoogleLoginException if any rule in the class description is violated
     */
    public String validateIdToken(final Map<String, Object> claims) {
        if (!ISSUERS.contains(String.valueOf(claims.get("iss")))) {
            throw new GoogleLoginException("Unexpected ID token issuer");
        }
        if (!audienceMatches(claims.get("aud"))) {
            throw new GoogleLoginException("ID token audience is not this client");
        }
        if (!(claims.get("exp") instanceof Number exp) || exp.longValue() <= clock.instant().getEpochSecond()) {
            throw new GoogleLoginException("ID token is expired");
        }
        final Object verified = claims.get("email_verified");
        if (!(Boolean.TRUE.equals(verified) || "true".equals(verified))) {
            throw new GoogleLoginException("Email is not verified");
        }
        if (!(claims.get("email") instanceof String email) || email.isBlank()) {
            throw new GoogleLoginException("ID token has no email");
        }
        if (!(claims.get("hd") instanceof String hostedDomain)
                || !allowedDomains.contains(hostedDomain.strip().toLowerCase(Locale.ROOT))) {
            throw new GoogleLoginException("Account does not belong to an allowed domain");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private boolean audienceMatches(final Object audience) {
        if (audience instanceof String single) {
            return single.equals(clientId);
        }
        return audience instanceof List<?> list && list.contains(clientId);
    }

    private Map<String, Object> claimsOf(final String idToken) throws IOException {
        final String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new GoogleLoginException("Malformed ID token");
        }
        final byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new GoogleLoginException("Malformed ID token", e);
        }
        return mapper.readValue(new String(payload, StandardCharsets.UTF_8), new TypeReference<>() { });
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
