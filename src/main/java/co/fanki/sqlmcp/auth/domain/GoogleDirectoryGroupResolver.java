package co.fanki.sqlmcp.auth.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a user's groups from the Google Workspace Admin SDK Directory API.
 *
 * <p>Business rules: a user that does not exist or is suspended has no groups; nested groups are expanded
 * breadth-first with a visited set (so cycles end) up to a depth of 10; the result, including "no groups", is cached
 * per lower-cased email for {@code groups-cache-ttl}; when Google fails and there is no valid cached value the
 * resolution fails with {@link GroupResolutionException} (fail closed). The credentials are built on first use, so a bad key denies instead of preventing startup.
 *
 * @author waabox(emiliano[at]fanki[dot]co)
 */
@Component
@Primary
@ConditionalOnProperty(name = "sql-mcp.auth.google.enabled", havingValue = "true")
public class GoogleDirectoryGroupResolver implements GroupResolver {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleDirectoryGroupResolver.class);
    private static final String SCOPE_GROUPS = "https://www.googleapis.com/auth/admin.directory.group.readonly";
    private static final String SCOPE_USERS = "https://www.googleapis.com/auth/admin.directory.user.readonly";
    private static final int MAX_DEPTH = 10;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String directoryUrl;
    private final Duration cacheTtl;
    private final Clock clock;
    private final String serviceAccountKey;
    private final String adminEmail;
    private GoogleCredentials credentials;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Creates the resolver.
     *
     * @param settings the authentication settings (service account key, admin email, directory URL, cache TTL)
     * @param clock the clock used to expire cache entries
     */
    public GoogleDirectoryGroupResolver(final AuthSettings settings, final Clock clock) {
        final AuthSettings.Google google = settings.getGoogle();
        this.directoryUrl = google.getDirectoryUrl().replaceAll("/+$", "");
        this.cacheTtl = google.getGroupsCacheTtl();
        this.clock = clock;
        this.serviceAccountKey = google.getServiceAccountKey();
        this.adminEmail = google.getAdminEmail();
        this.http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /**
     * Returns the groups of a user, directly or through nested groups.
     *
     * @param email the user's email
     * @return the group emails (lower-cased), empty for an unknown or suspended user
     * @throws GroupResolutionException if Google cannot be queried and there is no valid cached value
     */
    @Override
    public Set<String> groupsOf(final String email) {
        final String key = email.toLowerCase(Locale.ROOT);
        final CacheEntry cached = cache.get(key);
        final Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt)) {
            return cached.groups;
        }
        final Set<String> groups = Set.copyOf(resolve(key));
        cache.put(key, new CacheEntry(groups, clock.instant().plus(cacheTtl)));
        return groups;
    }

    private Set<String> resolve(final String email) {
        try {
            if (!isActive(email)) {
                return Set.of();
            }
            final Set<String> visited = new HashSet<>();
            List<String> frontier = List.of(email);
            for (int depth = 0; depth < MAX_DEPTH && !frontier.isEmpty(); depth++) {
                final List<String> next = new ArrayList<>();
                for (final String member : frontier) {
                    for (final String group : groupsContaining(member)) {
                        if (visited.add(group)) {
                            next.add(group);
                        }
                    }
                }
                frontier = next;
            }
            return visited;
        } catch (final IOException e) {
            throw new GroupResolutionException("Could not read groups from Google", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GroupResolutionException("Interrupted while reading groups from Google", e);
        }
    }

    private boolean isActive(final String email) throws IOException, InterruptedException {
        final HttpResponse<String> response = get("/admin/directory/v1/users/" + pathSegment(email));
        if (response.statusCode() == 404) {
            return false;
        }
        requireOk(response);
        return !mapper.readTree(response.body()).path("suspended").asBoolean(false);
    }

    private List<String> groupsContaining(final String userKey) throws IOException, InterruptedException {
        final List<String> groups = new ArrayList<>();
        String pageToken = null;
        do {
            String path = "/admin/directory/v1/groups?userKey=" + encode(userKey);
            if (pageToken != null) {
                path += "&pageToken=" + encode(pageToken);
            }
            final HttpResponse<String> response = get(path);
            requireOk(response);
            final JsonNode body = mapper.readTree(response.body());
            for (final JsonNode group : body.path("groups")) {
                final String groupEmail = group.path("email").asText("");
                if (!groupEmail.isBlank()) {
                    groups.add(groupEmail.toLowerCase(Locale.ROOT));
                }
            }
            pageToken = body.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isEmpty());
        return groups;
    }

    private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(directoryUrl + path))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken())
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private synchronized String accessToken() throws IOException {
        if (credentials == null) {
            final ServiceAccountCredentials account;
            try {
                account = ServiceAccountCredentials.fromStream(
                        new ByteArrayInputStream(serviceAccountKey.getBytes(StandardCharsets.UTF_8)));
            } catch (final IOException e) {
                throw new GroupResolutionException("sql-mcp.auth.google.service-account-key is not a valid key", e);
            }
            credentials = account.createDelegated(adminEmail).createScoped(List.of(SCOPE_GROUPS, SCOPE_USERS));
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private static void requireOk(final HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            LOG.warn("Google Directory API answered HTTP {}", response.statusCode());
            throw new GroupResolutionException("Google Directory API answered HTTP " + response.statusCode());
        }
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String pathSegment(final String value) {
        return encode(value).replace("%40", "@");
    }

    private static final class CacheEntry {

        private final Set<String> groups;
        private final Instant expiresAt;

        private CacheEntry(final Set<String> groups, final Instant expiresAt) {
            this.groups = groups;
            this.expiresAt = expiresAt;
        }
    }

}
