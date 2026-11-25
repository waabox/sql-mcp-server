package co.fanki.sqlmcp.auth.application;

import co.fanki.sqlmcp.SqlMcpServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for access granted through an explicit user list, with Google login enabled but no service
 * account and no admin email: Google is faked by WireMock (token endpoint only) and PostgreSQL runs in Testcontainers.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Testcontainers
class UserListAccessIntegrationTest {

    private static final String REDIRECT_URI = "http://localhost:9999/callback";
    private static final String VERIFIER = "a-very-long-code-verifier-0123456789-0123456789-0123456789";
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
        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        issuer = "http://localhost:" + port;
        final List<String> all = new ArrayList<>(List.of(
                "sql-mcp.transport=http",
                "server.port=" + port,
                "spring.main.web-application-type=servlet",
                "sql-mcp.connections[0].name=analytics",
                "sql-mcp.connections[1].name=billing",
                "sql-mcp.http.auth-token=shared-secret",
                "sql-mcp.access[0].users[0]=Ana@example.com",
                "sql-mcp.access[0].connections[0]=analytics",
                "sql-mcp.auth.issuer=" + issuer,
                "sql-mcp.auth.signing-key=0123456789abcdef0123456789abcdef",
                "sql-mcp.auth.google.enabled=true",
                "sql-mcp.auth.google.client-id=google-client-id",
                "sql-mcp.auth.google.client-secret=google-client-secret",
                "sql-mcp.auth.google.allowed-domains=example.com",
                "sql-mcp.auth.google.authorize-url=http://localhost:" + google.port() + "/google-auth",
                "sql-mcp.auth.google.token-url=http://localhost:" + google.port() + "/google-token",
                "sql-mcp.auth.google.directory-url=http://localhost:" + google.port()));
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

    private String idToken(final String email) throws Exception {
        final Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("aud", "google-client-id");
        claims.put("exp", Instant.now().getEpochSecond() + 3600);
        claims.put("email", email);
        claims.put("email_verified", true);
        claims.put("hd", "example.com");
        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(MAPPER.writeValueAsBytes(claims)) + ".sig";
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
        for (final String pair : URI.create(location).getRawQuery().split("&")) {
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

    /** Runs the whole login as the given Google user and returns the access token. */
    private String loginAs(final String email) throws Exception {
        google.stubFor(post(urlPathEqualTo("/google-token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id_token\":\"" + idToken(email) + "\"}")));
        final HttpResponse<String> registration = http.send(
                HttpRequest.newBuilder(URI.create(issuer + "/oauth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"client_name\":\"Test Client\","
                                + "\"redirect_uris\":[\"" + REDIRECT_URI + "\"]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, registration.statusCode(), registration.body());
        final String clientId = MAPPER.readTree(registration.body()).get("client_id").asText();

        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.UTF_8));
        final HttpResponse<String> authorize = httpGet("/oauth/authorize?" + form(Map.of(
                "response_type", "code",
                "client_id", clientId,
                "redirect_uri", REDIRECT_URI,
                "code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
                "code_challenge_method", "S256",
                "state", "client-state")));
        assertEquals(302, authorize.statusCode());
        final String state = queryOf(authorize.headers().firstValue("Location").orElseThrow()).get("state");

        final HttpResponse<String> callback = httpGet("/oauth/google/callback?code=google-code&state=" + enc(state));
        assertEquals(200, callback.statusCode(), callback.body());
        final Matcher matcher = Pattern.compile("name=\"login_state\" value=\"([^\"]+)\"").matcher(callback.body());
        assertTrue(matcher.find(), callback.body());

        final HttpResponse<String> consent = postForm("/oauth/consent",
                Map.of("login_state", matcher.group(1), "decision", "approve"));
        assertEquals(302, consent.statusCode(), consent.body());
        final String code = queryOf(consent.headers().firstValue("Location").orElseThrow()).get("code");

        final HttpResponse<String> token = postForm("/oauth/token", Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "redirect_uri", REDIRECT_URI,
                "client_id", clientId,
                "code_verifier", VERIFIER));
        assertEquals(200, token.statusCode(), token.body());
        final JsonNode tokens = MAPPER.readTree(token.body());
        return tokens.get("access_token").asText();
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
    void whenRunningFullFlow_givenListedUser_shouldListOnlyAnalyticsAndQueryIt() throws Exception {
        final String accessToken = loginAs("ana@example.com");

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
    void whenQuerying_givenUnlistedUser_shouldGetSameErrorAsUnknownConnection() throws Exception {
        final String accessToken = loginAs("pedro@example.com");

        final String forbidden = callTool(accessToken, "execute_query",
                "{\"connection\":\"analytics\",\"query\":\"SELECT 1\"}").body();
        final String unknown = callTool(accessToken, "execute_query",
                "{\"connection\":\"does-not-exist\",\"query\":\"SELECT 1\"}").body();

        assertTrue(forbidden.contains("Connection profile not found"), forbidden);
        assertEquals(forbidden.replace("analytics", "X"), unknown.replace("does-not-exist", "X"));
        assertFalse(callTool(accessToken, "list_connections", "{}").body().contains("analytics"));
    }

    @Test
    void whenRunningFullFlow_givenUsersOnlyPolicy_shouldNeverCallDirectoryApi() throws Exception {
        final String accessToken = loginAs("ana@example.com");
        callTool(accessToken, "list_connections", "{}");
        callTool(accessToken, "execute_query", "{\"connection\":\"analytics\",\"query\":\"SELECT 1\"}");
        final String pedro = loginAs("pedro@example.com");
        callTool(pedro, "list_connections", "{}");

        assertEquals(0, google.findAll(anyRequestedFor(urlPathMatching("/admin/directory.*"))).size());
        assertEquals(0, google.findAll(anyRequestedFor(urlPathEqualTo("/sa-token"))).size());
    }

}
