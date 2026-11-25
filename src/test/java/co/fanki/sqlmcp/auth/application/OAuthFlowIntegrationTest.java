package co.fanki.sqlmcp.auth.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.fanki.sqlmcp.SqlMcpServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the OAuth login flow, through the full Spring context and a real HTTP server, with Google
 * faked by WireMock and a real PostgreSQL in Testcontainers.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Testcontainers
class OAuthFlowIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:9999/callback";
    private static final String VERIFIER = "a-very-long-code-verifier-0123456789-0123456789-0123456789";
    private static final String CLIENT_NAME = "Test Client";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private WireMockServer google;
    private ConfigurableApplicationContext context;
    private String issuer;

    @BeforeEach
    void setUp() throws Exception {
        google = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        google.start();
        google.stubFor(post(urlPathEqualTo("/sa-token")).willReturn(json(
                "{\"access_token\":\"fake-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
        google.stubFor(post(urlPathEqualTo("/google-token")).willReturn(json(
                "{\"id_token\":\"" + idToken() + "\"}")));
        google.stubFor(get(urlPathEqualTo("/admin/directory/v1/users/ana@example.com"))
                .willReturn(json("{\"primaryEmail\":\"ana@example.com\"}")));
        stubGroups("ana@example.com", "{\"groups\":[{\"email\":\"data@example.com\"}]}");
        stubGroups("data@example.com", "{}");

        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        issuer = "http://localhost:" + port;
        final List<String> properties = List.of(
                "sql-mcp.transport=http",
                "server.port=" + port,
                "spring.main.web-application-type=servlet",
                "sql-mcp.connections[0].name=analytics",
                "sql-mcp.connections[1].name=billing",
                "sql-mcp.access[0].group=data@example.com",
                "sql-mcp.access[0].connections[0]=analytics",
                "sql-mcp.auth.issuer=" + issuer,
                "sql-mcp.auth.signing-key=0123456789abcdef0123456789abcdef",
                "sql-mcp.auth.google.enabled=true",
                "sql-mcp.auth.google.client-id=google-client-id",
                "sql-mcp.auth.google.client-secret=google-client-secret",
                "sql-mcp.auth.google.allowed-domains=example.com",
                "sql-mcp.auth.google.service-account-key=" + serviceAccountKey(),
                "sql-mcp.auth.google.admin-email=admin@example.com",
                "sql-mcp.auth.google.authorize-url=http://localhost:" + google.port() + "/google-auth",
                "sql-mcp.auth.google.token-url=http://localhost:" + google.port() + "/google-token",
                "sql-mcp.auth.google.directory-url=http://localhost:" + google.port());
        final List<String> all = new java.util.ArrayList<>(properties);
        for (int i = 0; i < 2; i++) {
            all.add("sql-mcp.connections[" + i + "].type=postgresql");
            all.add("sql-mcp.connections[" + i + "].host=" + postgres.getHost());
            all.add("sql-mcp.connections[" + i + "].port=" + postgres.getMappedPort(5432));
            all.add("sql-mcp.connections[" + i + "].database=" + postgres.getDatabaseName());
            all.add("sql-mcp.connections[" + i + "].username=" + postgres.getUsername());
            all.add("sql-mcp.connections[" + i + "].password=" + postgres.getPassword());
        }
        context = new SpringApplicationBuilder(SqlMcpServerApplication.class)
                .run(all.stream().map(property -> "--" + property).toArray(String[]::new));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        google.stop();
    }

    private void stubGroups(final String userKey, final String body) {
        google.stubFor(get(urlPathEqualTo("/admin/directory/v1/groups"))
                .withQueryParam("userKey", equalTo(userKey)).willReturn(json(body)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(final String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    private String idToken() throws Exception {
        final Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", "google-client-id");
        claims.put("exp", Instant.now().getEpochSecond() + 3600);
        claims.put("email", "ana@example.com");
        claims.put("email_verified", true);
        claims.put("hd", "example.com");
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(MAPPER.writeValueAsBytes(claims)) + ".sig";
    }

    private String serviceAccountKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        final String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----\n";
        return "{\"type\":\"service_account\",\"project_id\":\"p\",\"private_key_id\":\"k1\","
                + "\"private_key\":\"" + pem.replace("\n", "\\n") + "\","
                + "\"client_email\":\"sa@p.iam.gserviceaccount.com\",\"client_id\":\"1\","
                + "\"token_uri\":\"http://localhost:" + google.port() + "/sa-token\"}";
    }

    private static String challenge() throws Exception {
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String enc(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String form(final Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> enc(entry.getKey()) + "=" + enc(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> queryOf(final String location) {
        final Map<String, String> result = new LinkedHashMap<>();
        final String query = URI.create(location).getRawQuery();
        for (final String pair : query.split("&")) {
            final String[] kv = pair.split("=", 2);
            result.put(kv[0], URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private HttpResponse<String> httpGet(final String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(issuer + pathAndQuery)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(final String path, final Map<String, String> fields) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(issuer + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String register() throws Exception {
        final HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(issuer + "/oauth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"client_name\":\"" + CLIENT_NAME
                        + "\",\"redirect_uris\":[\"" + REDIRECT_URI + "\"]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        return MAPPER.readTree(response.body()).get("client_id").asText();
    }

    private String authorizePath(final String clientId, final String redirectUri) throws Exception {
        return "/oauth/authorize?" + form(Map.of(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", redirectUri,
                "code_challenge", challenge(),
                "code_challenge_method", "S256",
                "state", "client-state"));
    }

    /** Runs register, authorize and the Google callback; returns the login state from the consent page. */
    private String reachConsent(final String clientId) throws Exception {
        final HttpResponse<String> authorize = httpGet(authorizePath(clientId, REDIRECT_URI));
        assertEquals(302, authorize.statusCode());
        final String location = authorize.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith("http://localhost:" + google.port() + "/google-auth"), location);
        final String state = queryOf(location).get("state");

        final HttpResponse<String> callback = httpGet("/oauth/google/callback?code=google-code&state=" + enc(state));
        assertEquals(200, callback.statusCode(), callback.body());
        assertTrue(callback.body().contains(CLIENT_NAME), callback.body());
        final Matcher matcher = Pattern.compile("name=\"login_state\" value=\"([^\"]+)\"").matcher(callback.body());
        assertTrue(matcher.find(), callback.body());
        return matcher.group(1);
    }

    private String approve(final String loginState) throws Exception {
        final HttpResponse<String> consent = postForm("/oauth/consent",
                Map.of("login_state", loginState, "decision", "approve"));
        assertEquals(302, consent.statusCode(), consent.body());
        final Map<String, String> query = queryOf(consent.headers().firstValue("Location").orElseThrow());
        assertEquals("client-state", query.get("state"));
        return query.get("code");
    }

    private HttpResponse<String> exchange(final String clientId, final String code, final String verifier)
            throws Exception {
        return postForm("/oauth/token", Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT_URI,
                "client_id", clientId,
                "code_verifier", verifier));
    }

    private JsonNode login() throws Exception {
        final String clientId = register();
        final String code = approve(reachConsent(clientId));
        final HttpResponse<String> token = exchange(clientId, code, VERIFIER);
        assertEquals(200, token.statusCode(), token.body());
        return MAPPER.readTree(token.body());
    }

    private HttpResponse<String> callTool(final String accessToken, final String name, final String arguments)
            throws Exception {
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + name + "\",\"arguments\":" + arguments + "}}";
        return http.send(HttpRequest.newBuilder(URI.create(issuer + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void whenRunningFullFlow_givenUserInDataGroup_shouldListOnlyAnalyticsAndQueryIt() throws Exception {
        final JsonNode tokens = login();
        assertEquals("Bearer", tokens.get("token_type").asText());
        final String accessToken = tokens.get("access_token").asText();

        final HttpResponse<String> list = callTool(accessToken, "list_connections", "{}");
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("analytics"), list.body());
        assertFalse(list.body().contains("billing"), list.body());

        final HttpResponse<String> query = callTool(accessToken, "execute_query",
                "{\"connection\":\"analytics\",\"query\":\"SELECT 1 AS one\"}");
        assertEquals(200, query.statusCode(), query.body());
        assertTrue(query.body().contains("one"), query.body());
        assertFalse(query.body().contains("\\\"success\\\":false"), query.body());
    }

    @Test
    void whenQueryingForbiddenConnection_givenUserInDataGroup_shouldReturnSameErrorAsUnknownConnection()
            throws Exception {
        final String accessToken = login().get("access_token").asText();

        final String forbidden = callTool(accessToken, "execute_query",
                "{\"connection\":\"billing\",\"query\":\"SELECT 1\"}").body();
        final String unknown = callTool(accessToken, "execute_query",
                "{\"connection\":\"does-not-exist\",\"query\":\"SELECT 1\"}").body();

        assertTrue(forbidden.contains("Connection profile not found"), forbidden);
        assertEquals(forbidden.replace("billing", "X"), unknown.replace("does-not-exist", "X"));
    }

    @Test
    void whenQueryingForbiddenConnection_givenUserInDataGroup_shouldLogWarningWithCallerAndConnection()
            throws Exception {
        final String accessToken = login().get("access_token").asText();
        final Logger config = (Logger) LoggerFactory.getLogger("co.fanki.sqlmcp.config.McpServerConfig");
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        config.addAppender(appender);
        try {
            callTool(accessToken, "execute_query", "{\"connection\":\"billing\",\"query\":\"SELECT 1\"}");
        } finally {
            config.detachAppender(appender);
        }

        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains("ana@example.com")
                && event.getFormattedMessage().contains("billing")), appender.list.toString());
    }

    @Test
    void whenExchangingCode_givenWrongCodeVerifier_shouldReturnInvalidGrant() throws Exception {
        final String clientId = register();
        final String code = approve(reachConsent(clientId));

        final HttpResponse<String> response = exchange(clientId, code, VERIFIER + "-tampered");

        assertEquals(400, response.statusCode());
        assertEquals("invalid_grant", MAPPER.readTree(response.body()).get("error").asText());
    }

    @Test
    void whenAuthorizing_givenRedirectUriNotRegistered_shouldNotRedirect() throws Exception {
        final String clientId = register();

        final HttpResponse<String> response = httpGet(authorizePath(clientId, "https://evil.example/callback"));

        assertEquals(400, response.statusCode());
        assertTrue(response.headers().firstValue("Location").isEmpty());
    }

    @Test
    void whenConsenting_givenDeny_shouldRedirectWithAccessDenied() throws Exception {
        final String loginState = reachConsent(register());

        final HttpResponse<String> response = postForm("/oauth/consent",
                Map.of("login_state", loginState, "decision", "deny"));

        assertEquals(302, response.statusCode());
        final String location = response.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith(REDIRECT_URI), location);
        final Map<String, String> query = queryOf(location);
        assertEquals("access_denied", query.get("error"));
        assertEquals("client-state", query.get("state"));
        assertFalse(query.containsKey("code"));
    }

    @Test
    void whenRefreshing_givenValidRefreshToken_shouldReturnNewAccessAndRefreshTokens() throws Exception {
        final String clientId = register();
        final String code = approve(reachConsent(clientId));
        final JsonNode first = MAPPER.readTree(exchange(clientId, code, VERIFIER).body());

        final HttpResponse<String> response = postForm("/oauth/token", Map.of(
                "grant_type", "refresh_token",
                "refresh_token", first.get("refresh_token").asText(),
                "client_id", clientId));

        assertEquals(200, response.statusCode(), response.body());
        final JsonNode second = MAPPER.readTree(response.body());
        assertNotEquals("", second.get("refresh_token").asText());
        assertEquals(200, callTool(second.get("access_token").asText(), "list_connections", "{}").statusCode());
    }

    @Test
    void whenQuerying_givenGoogleUser_shouldRecordEmailInAuditLog() throws Exception {
        final String accessToken = login().get("access_token").asText();
        final Logger audit = (Logger) LoggerFactory.getLogger("audit.query");
        final Level previous = audit.getLevel();
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        audit.setLevel(Level.INFO);
        audit.addAppender(appender);
        try {
            callTool(accessToken, "execute_query", "{\"connection\":\"analytics\",\"query\":\"SELECT 1 AS one\"}");
        } finally {
            audit.detachAppender(appender);
            audit.setLevel(previous);
        }

        assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage()
                .contains("\"clientInfo\":\"ana@example.com\"")), appender.list.toString());
    }

    @Test
    void whenReadingMetadata_givenGoogleEnabled_shouldAdvertiseEndpoints() throws Exception {
        final JsonNode resource = MAPPER.readTree(httpGet("/.well-known/oauth-protected-resource").body());
        final JsonNode server = MAPPER.readTree(httpGet("/.well-known/oauth-authorization-server").body());

        assertEquals(issuer + "/mcp", resource.get("resource").asText());
        assertEquals(issuer, resource.get("authorization_servers").get(0).asText());
        assertEquals(issuer + "/oauth/token", server.get("token_endpoint").asText());
        assertEquals("S256", server.get("code_challenge_methods_supported").get(0).asText());
    }

}
