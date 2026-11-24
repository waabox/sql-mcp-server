package co.fanki.sqlmcp.auth.domain;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GoogleDirectoryGroupResolver}, with Google faked by WireMock.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
class GoogleDirectoryGroupResolverTest {

    private static final String USERS = "/admin/directory/v1/users/ana@example.com";
    private static final String GROUPS = "/admin/directory/v1/groups";

    private WireMockServer google;

    @BeforeEach
    void setUp() throws Exception {
        google = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        google.start();
        google.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"fake-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
        stubActiveUser();
    }

    @AfterEach
    void tearDown() {
        google.stop();
    }

    private GoogleDirectoryGroupResolver resolver(final Duration cacheTtl) throws Exception {
        final AuthSettings settings = new AuthSettings();
        settings.getGoogle().setServiceAccountKey(serviceAccountKey());
        settings.getGoogle().setAdminEmail("admin@example.com");
        settings.getGoogle().setDirectoryUrl("http://localhost:" + google.port());
        settings.getGoogle().setGroupsCacheTtl(cacheTtl);
        return new GoogleDirectoryGroupResolver(settings, Clock.systemUTC());
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
                + "\"token_uri\":\"http://localhost:" + google.port() + "/token\"}";
    }

    private void stubActiveUser() {
        google.stubFor(get(urlPathEqualTo(USERS)).willReturn(json("{\"primaryEmail\":\"ana@example.com\"}")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(final String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    private void stubGroups(final String userKey, final String body) {
        google.stubFor(get(urlPathEqualTo(GROUPS)).withQueryParam("userKey", equalTo(userKey))
                .willReturn(json(body)));
    }

    @Test
    void whenResolvingGroups_givenUserInOneGroupNestedInAnother_shouldReturnBoth() throws Exception {
        stubGroups("ana@example.com", "{\"groups\":[{\"email\":\"data@example.com\"}]}");
        stubGroups("data@example.com", "{\"groups\":[{\"email\":\"all-staff@example.com\"}]}");
        stubGroups("all-staff@example.com", "{}");

        final Set<String> groups = resolver(Duration.ofMinutes(5)).groupsOf("ana@example.com");

        assertEquals(Set.of("data@example.com", "all-staff@example.com"), groups);
    }

    @Test
    void whenResolvingGroups_givenGroupCycle_shouldTerminateAndReturnBoth() throws Exception {
        stubGroups("ana@example.com", "{\"groups\":[{\"email\":\"a@example.com\"}]}");
        stubGroups("a@example.com", "{\"groups\":[{\"email\":\"b@example.com\"}]}");
        stubGroups("b@example.com", "{\"groups\":[{\"email\":\"a@example.com\"}]}");

        final Set<String> groups = resolver(Duration.ofMinutes(5)).groupsOf("ana@example.com");

        assertEquals(Set.of("a@example.com", "b@example.com"), groups);
    }

    @Test
    void whenResolvingGroups_givenSuspendedUser_shouldReturnEmpty() throws Exception {
        google.stubFor(get(urlPathEqualTo(USERS)).willReturn(json("{\"suspended\":true}")));
        stubGroups("ana@example.com", "{\"groups\":[{\"email\":\"data@example.com\"}]}");

        assertTrue(resolver(Duration.ofMinutes(5)).groupsOf("ana@example.com").isEmpty());
    }

    @Test
    void whenResolvingGroups_givenUnknownUser_shouldReturnEmpty() throws Exception {
        google.stubFor(get(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(404)));

        assertTrue(resolver(Duration.ofMinutes(5)).groupsOf("ana@example.com").isEmpty());
    }

    @Test
    void whenResolvingGroups_givenPagedResults_shouldReadAllPages() throws Exception {
        google.stubFor(get(urlPathEqualTo(GROUPS)).withQueryParam("userKey", equalTo("ana@example.com"))
                .withQueryParam("pageToken", equalTo("page2"))
                .willReturn(json("{\"groups\":[{\"email\":\"two@example.com\"}]}")));
        google.stubFor(get(urlPathEqualTo(GROUPS)).withQueryParam("userKey", equalTo("ana@example.com"))
                .withQueryParam("pageToken", absent())
                .willReturn(json("{\"groups\":[{\"email\":\"one@example.com\"}],\"nextPageToken\":\"page2\"}")));
        stubGroups("one@example.com", "{}");
        stubGroups("two@example.com", "{}");

        final Set<String> groups = resolver(Duration.ofMinutes(5)).groupsOf("ana@example.com");

        assertEquals(Set.of("one@example.com", "two@example.com"), groups);
    }

    @Test
    void whenResolvingGroups_givenCachedResult_shouldNotCallGoogleAgain() throws Exception {
        stubGroups("ana@example.com", "{\"groups\":[{\"email\":\"data@example.com\"}]}");
        stubGroups("data@example.com", "{}");
        final GoogleDirectoryGroupResolver resolver = resolver(Duration.ofMinutes(5));

        resolver.groupsOf("ana@example.com");
        final Set<String> second = resolver.groupsOf("Ana@Example.com");

        assertEquals(Set.of("data@example.com"), second);
        google.verify(1, getRequestedFor(urlPathEqualTo(USERS)));
    }

    @Test
    void whenResolvingGroups_givenGoogleDownAndNoCache_shouldThrow() throws Exception {
        google.stubFor(get(urlPathEqualTo(USERS)).willReturn(aResponse().withStatus(503)));

        final GoogleDirectoryGroupResolver resolver = resolver(Duration.ofMinutes(5));

        assertThrows(GroupResolutionException.class, () -> resolver.groupsOf("ana@example.com"));
    }

    @Test
    void whenResolvingGroups_givenUserSuspendedAfterCacheExpired_shouldReturnEmpty() throws Exception {
        stubGroups("ana@example.com", "{\"groups\":[{\"email\":\"data@example.com\"}]}");
        stubGroups("data@example.com", "{}");
        final GoogleDirectoryGroupResolver resolver = resolver(Duration.ZERO);
        assertEquals(Set.of("data@example.com"), resolver.groupsOf("ana@example.com"));

        google.stubFor(get(urlPathEqualTo(USERS)).willReturn(json("{\"suspended\":true}")));

        assertTrue(resolver.groupsOf("ana@example.com").isEmpty());
    }

}
