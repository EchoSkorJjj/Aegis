package com.aegis.core.actions.pr.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aegis.core.platform.common.ApiExceptions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The GitHub user's side of connecting an installation: trade the sign-in's code for a short-lived user
 * token, ask GitHub who they are, which of the App's installations they can see and whether they administer
 * a GitHub organization — then revoke the token. The token never leaves this class and is never stored;
 * like {@link GitHubApi}, no upstream error, header or body is passed on.
 */
final class GitHubUserAuthorization {

    private static final Logger log = LoggerFactory.getLogger(GitHubUserAuthorization.class);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    /** Enough for anyone's installations of one App; the listing stops here rather than paging forever. */
    private static final int MAX_INSTALLATION_PAGES = 10;
    private static final String LOGIN = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})";

    private final GitHubProperties properties;
    private final GitHubApi api;
    private final ObjectMapper mapper;
    private final RestClient web;

    GitHubUserAuthorization(GitHubProperties properties, GitHubApi api, ObjectMapper mapper,
            ObservationRegistry observations) {
        this.properties = properties;
        this.api = api;
        this.mapper = mapper;
        var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(30));
        web = RestClient.builder().requestFactory(factory).observationRegistry(observations).build();
    }

    /** A GitHub user or organization account. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(long id, String login, String type) {
    }

    /** One of the App's installations, and the account it is installed on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Installation(long id, Account account) {
    }

    /** The user token for the sign-in's {@code code}, or a conflict when GitHub refuses the code. */
    String token(String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.callbackUrl().toString());
        URI uri = UriComponentsBuilder.fromUri(properties.webBaseUrl()).path("/login/oauth/access_token")
                .build().encode().toUri();
        TokenResponse response;
        try {
            response = web.post().uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((request, reply) -> {
                        if (!reply.getStatusCode().is2xxSuccessful()) {
                            throw new ApiExceptions.UpstreamUnavailable(
                                    "GitHub sign-in failed (HTTP " + reply.getStatusCode().value() + ")");
                        }
                        try {
                            byte[] bytes = reply.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                            if (bytes.length > MAX_RESPONSE_BYTES) {
                                throw GitHubApi.invalidResponse();
                            }
                            TokenResponse parsed = mapper.readValue(bytes, TokenResponse.class);
                            if (parsed == null) {
                                throw GitHubApi.invalidResponse();
                            }
                            return parsed;
                        } catch (IOException | JacksonException malformed) {
                            // Sanitise here: the body holds the token, and tracing records callback failures.
                            throw GitHubApi.invalidResponse();
                        }
                    });
        } catch (RestClientException | JacksonException unreachable) {
            throw new ApiExceptions.UpstreamUnavailable("GitHub sign-in failed or returned an invalid response");
        }
        if (response.error() != null) {
            // GitHub answers a bad or expired code with HTTP 200 and an error field.
            throw new ApiExceptions.Conflict("GitHub didn't accept the sign-in, which may have expired; connect again");
        }
        String token = response.accessToken();
        if (token == null || !token.matches("[\\x21-\\x7e]+")) {
            throw GitHubApi.invalidResponse();
        }
        return token;
    }

    /** Who signed in. */
    Account user(String token) {
        Account user = api.call(HttpMethod.GET, api.uri("user", null), token, null, Account.class, false,
                "GitHub user not found");
        if (user.login() == null || user.id() <= 0) {
            throw GitHubApi.invalidResponse();
        }
        return user;
    }

    /** The App's installations the signed-in user can see: their own, and those of organizations they belong to. */
    List<Installation> installations(String token) {
        List<Installation> installations = new ArrayList<>();
        for (int page = 1; page <= MAX_INSTALLATION_PAGES; page++) {
            InstallationPage listed = api.call(HttpMethod.GET,
                    api.uriWithQuery("user/installations", Map.of("per_page", "100", "page", Integer.toString(page))),
                    token, null, InstallationPage.class, false, "GitHub installations not found");
            List<Installation> onPage = listed.installations() == null ? List.of() : listed.installations();
            onPage.stream()
                    .filter(installation -> installation.id() > 0 && installation.account() != null
                            && installation.account().login() != null && installation.account().login().matches(LOGIN)
                            && installation.account().id() > 0)
                    .forEach(installations::add);
            if (onPage.size() < 100 || installations.size() >= listed.totalCount()) {
                break;
            }
        }
        return installations;
    }

    /**
     * Whether the signed-in user is an active admin of this GitHub organization — or empty when GitHub won't
     * say, because the App was not granted read access to the organization's members.
     */
    Optional<Boolean> administers(String token, String organization) {
        if (!organization.matches(LOGIN)) {
            return Optional.of(false);
        }
        try {
            Membership membership = api.call(HttpMethod.GET, api.uri("user/memberships/orgs/" + organization, null),
                    token, null, Membership.class, true, null);
            return Optional.of(membership != null && "active".equals(membership.state())
                    && "admin".equals(membership.role()));
        } catch (ApiExceptions.UpstreamUnavailable refused) {
            return Optional.empty();
        }
    }

    /** Revoke the user token now it has served its purpose. Best effort: it also expires on its own. */
    void revoke(String token) {
        String credentials = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            api.exchange(HttpMethod.DELETE, api.uri("applications/" + properties.clientId() + "/token", null),
                    "Basic " + credentials, Map.of("access_token", token), Void.class, true, null);
        } catch (RuntimeException failed) {
            log.debug("GitHub user token could not be revoked early ({}); it expires on its own",
                    failed.getClass().getSimpleName());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(@JsonProperty("access_token") String accessToken, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InstallationPage(@JsonProperty("total_count") int totalCount, List<Installation> installations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Membership(String state, String role) {
    }
}
