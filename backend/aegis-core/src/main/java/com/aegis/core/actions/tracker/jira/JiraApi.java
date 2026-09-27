package com.aegis.core.actions.tracker.jira;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Atlassian's OAuth and Jira REST APIs, from the two fixed origins in {@link JiraProperties}: no redirects,
 * bounded responses, and no upstream error, header, body or token ever passed on.
 */
final class JiraApi {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final String CLOUD_ID = "[0-9A-Fa-f-]{8,64}";
    static final String PROJECT_KEY = "[A-Z][A-Z0-9_]{0,254}";

    private final JiraProperties properties;
    private final ObjectMapper mapper;
    private final RestClient http;

    JiraApi(JiraProperties properties, ObjectMapper mapper, ObservationRegistry observations) {
        this.properties = properties;
        this.mapper = mapper;
        var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(30));
        http = RestClient.builder().requestFactory(factory).observationRegistry(observations)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
    }

    /** An access token, and the refresh token that replaces the last one (Atlassian rotates them). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Tokens(@JsonProperty("access_token") String accessToken,
                  @JsonProperty("refresh_token") String refreshToken,
                  @JsonProperty("expires_in") long expiresIn) {
    }

    /** A Jira site the signed-in person granted Aegis, and what they granted there. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Site(String id, String name, String url, List<String> scopes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Project(String id, String key, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record IssueType(String id, String name, boolean subtask) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Created(String id, String key) {
    }

    /** Jira refused the access token: it expired or was revoked early. The caller refreshes it once. */
    static final class Unauthorized extends RuntimeException {
        Unauthorized() {
            super("Jira refused the access token");
        }
    }

    /** Jira rejected these fields of a new issue (a priority or due date the site doesn't have, say). */
    static final class FieldsRejected extends RuntimeException {
        private final Set<String> fields;

        FieldsRejected(Set<String> fields) {
            super("Jira rejected fields " + fields);
            this.fields = Set.copyOf(fields);
        }

        Set<String> fields() {
            return fields;
        }
    }

    /** The tokens for an admin's sign-in. */
    Tokens exchange(String code) {
        Tokens tokens = token(Map.of("grant_type", "authorization_code", "client_id", properties.clientId(),
                "client_secret", properties.clientSecret(), "code", code,
                "redirect_uri", properties.callbackUrl().toString()),
                "Atlassian didn't accept the sign-in, which may have expired; connect Jira again");
        if (tokens.refreshToken() == null || !safeToken(tokens.refreshToken())) {
            throw new ApiExceptions.Conflict("Atlassian granted no refresh token; connect Jira again and allow offline access");
        }
        return tokens;
    }

    /** New tokens for a refresh token, which is spent: the new refresh token replaces it. */
    Tokens refresh(String refreshToken) {
        return token(Map.of("grant_type", "refresh_token", "client_id", properties.clientId(),
                "client_secret", properties.clientSecret(), "refresh_token", refreshToken),
                "The Jira connection expired or was revoked; an organization admin must connect Jira again");
    }

    /** The Jira sites this token may reach. */
    List<Site> sites(String accessToken) {
        URI uri = UriComponentsBuilder.fromUri(properties.apiBaseUrl()).path("/oauth/token/accessible-resources")
                .build().toUri();
        return List.of(call(HttpMethod.GET, uri, accessToken, null, Site[].class));
    }

    /** The site's projects the connected account may create issues in. */
    List<Project> projects(String cloudId, String accessToken) {
        URI uri = jira(cloudId, "project/search").queryParam("action", "create").queryParam("maxResults", 100)
                .build().toUri();
        return List.copyOf(call(HttpMethod.GET, uri, accessToken, null, ProjectPage.class).values());
    }

    /** The project's issue types, sub-tasks excluded. */
    List<IssueType> issueTypes(String cloudId, String accessToken, String projectKey) {
        requireProjectKey(projectKey);
        URI uri = jira(cloudId, "project/" + projectKey).build().toUri();
        ProjectDetail project = call(HttpMethod.GET, uri, accessToken, null, ProjectDetail.class);
        return project.issueTypes() == null ? List.of()
                : project.issueTypes().stream().filter(type -> !type.subtask()).toList();
    }

    Created createIssue(String cloudId, String accessToken, Map<String, Object> fields) {
        URI uri = jira(cloudId, "issue").build().toUri();
        Created created = call(HttpMethod.POST, uri, accessToken, Map.of("fields", fields), Created.class);
        if (created.key() == null || !created.key().matches(PROJECT_KEY + "-[0-9]+")) {
            throw invalidResponse();
        }
        return created;
    }

    static void requireProjectKey(String projectKey) {
        if (projectKey == null || !projectKey.matches(PROJECT_KEY)) {
            throw new ApiExceptions.BadRequest("A Jira project key is capital letters, digits and underscores");
        }
    }

    static ApiExceptions.UpstreamUnavailable invalidResponse() {
        return new ApiExceptions.UpstreamUnavailable("Jira returned an invalid response");
    }

    private UriComponentsBuilder jira(String cloudId, String path) {
        if (cloudId == null || !cloudId.matches(CLOUD_ID)) {
            throw new ApiExceptions.BadRequest("Not a Jira site id");
        }
        return UriComponentsBuilder.fromUri(properties.apiBaseUrl())
                .pathSegment("ex", "jira", cloudId, "rest", "api", "3").path("/" + path);
    }

    private Tokens token(Map<String, String> body, String refused) {
        URI uri = UriComponentsBuilder.fromUri(properties.authBaseUrl()).path("/oauth/token").build().toUri();
        Tokens tokens;
        try {
            tokens = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 400 || status == 401 || status == 403) {
                            throw new ApiExceptions.Conflict(refused);
                        }
                        if (status < 200 || status >= 300) {
                            throw new ApiExceptions.UpstreamUnavailable("Atlassian sign-in failed (HTTP " + status + ")");
                        }
                        return read(response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1), Tokens.class);
                    });
        } catch (RestClientException | JacksonException unreachable) {
            throw new ApiExceptions.UpstreamUnavailable("Atlassian sign-in failed or returned an invalid response");
        }
        if (tokens.accessToken() == null || !safeToken(tokens.accessToken()) || tokens.expiresIn() <= 0) {
            throw invalidResponse();
        }
        return tokens;
    }

    private <T> T call(HttpMethod method, URI uri, String accessToken, Object body, Class<T> type) {
        try {
            var request = http.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (status == 401) {
                    throw new Unauthorized();
                }
                if (status == 400 && method == HttpMethod.POST) {
                    throw new FieldsRejected(rejectedFields(bytes));
                }
                if (status == 403) {
                    throw new ApiExceptions.UpstreamUnavailable(
                            "Jira refused the request: the connected account may not create issues in that project");
                }
                if (status == 404) {
                    throw new ApiExceptions.NotFound("The Jira site or project was not found");
                }
                if (status < 200 || status >= 300) {
                    throw new ApiExceptions.UpstreamUnavailable("Jira request failed (HTTP " + status + ")");
                }
                return read(bytes, type);
            });
        } catch (RestClientException | JacksonException unreachable) {
            throw new ApiExceptions.UpstreamUnavailable("Jira request failed or returned an invalid response");
        }
    }

    /** The names of the fields Jira's 400 complains about; never the messages, which can quote input. */
    private Set<String> rejectedFields(byte[] bytes) {
        try {
            JsonNode errors = mapper.readTree(bytes).path("errors");
            return errors.isObject() ? Set.copyOf(errors.propertyNames()) : Set.of();
        } catch (JacksonException unreadable) {
            return Set.of();
        }
    }

    private <T> T read(byte[] bytes, Class<T> type) throws IOException {
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw invalidResponse();
        }
        try {
            T value = mapper.readValue(bytes, type);
            if (value == null) {
                throw invalidResponse();
            }
            return value;
        } catch (JacksonException malformed) {
            // Sanitise here: the body may hold a token, and tracing records callback failures.
            throw invalidResponse();
        }
    }

    private static boolean safeToken(String token) {
        return token.matches("[\\x21-\\x7e]+");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProjectPage(List<Project> values) {
        ProjectPage {
            values = values == null ? List.of() : values;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProjectDetail(String key, List<IssueType> issueTypes) {
    }
}
