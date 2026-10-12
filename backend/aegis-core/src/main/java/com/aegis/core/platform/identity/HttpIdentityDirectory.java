package com.aegis.core.platform.identity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aegis.core.platform.common.ApiExceptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * {@link IdentityDirectory} over identity-service's REST API ({@code GET /users/{id}}, {@code GET /users}).
 *
 * <p><b>Token relay, not a service credential.</b> The lookup carries the <em>caller's</em> bearer
 * token, so identity-service authorizes and org-scopes it exactly as it would the caller's own request:
 * it answers 404 for anyone outside the caller's active org. aegis-core therefore gains no privilege
 * it didn't already have through the user.
 *
 * <p>Builds its own {@link RestClient} rather than declaring a bean: the application's {@code RestClient}
 * bean targets the ai-service and carries its service token, and a second same-typed bean would force
 * every consumer to disambiguate. Timeouts are explicit so a slow identity-service can't pin a request
 * thread. The body is parsed with the application's Jackson mapper, tolerating fields added upstream.
 */
@Component
class HttpIdentityDirectory implements IdentityDirectory {

    /** Members per page of {@code GET /users}: within identity-service's page limit (Spring's default, 2,000). */
    static final int MEMBER_PAGE = 500;
    /** At most this many pages (100,000 members): a guard against a directory that never ends. */
    private static final int MAX_MEMBER_PAGES = 200;

    private final RestClient http;
    private final ObjectReader memberReader;
    private final ObjectReader memberPageReader;

    HttpIdentityDirectory(
            @Value("${aegis.identity.base-url:http://localhost:8081}") String baseUrl,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .build();
        this.memberReader = objectMapper.readerFor(DirectoryUser.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.memberPageReader = objectMapper.readerFor(MemberPage.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public Optional<DirectoryUser> findMember(UUID userId) {
        String token = callerToken();
        String body;
        try {
            body = http.get()
                    .uri("/users/{id}", userId)
                    .headers(headers -> headers.setBearerAuth(token))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound notInCallersOrg) {
            return Optional.empty();
        } catch (RestClientException unreachableOrFailed) {
            throw new ApiExceptions.UpstreamUnavailable("Could not verify the member with identity-service");
        }
        if (body == null || body.isBlank()) {
            throw new ApiExceptions.UpstreamUnavailable("identity-service returned an empty member record");
        }
        try {
            return Optional.of(memberReader.readValue(body));
        } catch (JacksonException malformed) {
            throw new ApiExceptions.UpstreamUnavailable("identity-service returned an unreadable member record");
        }
    }

    /**
     * Pages through {@code GET /users} (org-scoped by the caller's own token), {@value #MEMBER_PAGE} at a time by id, so
     * a member joining meanwhile can't shift a page. Stops at a page that isn't full.
     */
    @Override
    public List<DirectoryUser> members() {
        String token = callerToken();
        List<DirectoryUser> members = new ArrayList<>();
        for (int page = 0; page < MAX_MEMBER_PAGES; page++) {
            int number = page;
            String body;
            try {
                body = http.get()
                        .uri(uri -> uri.path("/users").queryParam("page", number).queryParam("size", MEMBER_PAGE)
                                .queryParam("sort", "id,asc").build())
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(String.class);
            } catch (RestClientException unreachableOrFailed) {
                throw new ApiExceptions.UpstreamUnavailable("Could not list the organization's members with "
                        + "identity-service");
            }
            List<DirectoryUser> content;
            try {
                MemberPage read = body == null || body.isBlank() ? null : memberPageReader.readValue(body);
                content = read == null || read.content() == null ? null : read.content();
            } catch (JacksonException malformed) {
                content = null;
            }
            if (content == null) {
                throw new ApiExceptions.UpstreamUnavailable("identity-service returned an unreadable member list");
            }
            members.addAll(content);
            if (content.size() < MEMBER_PAGE) {
                break;
            }
        }
        return members;
    }

    /** One page of {@code GET /users}: only its members are read. */
    record MemberPage(List<DirectoryUser> content) {
    }

    /** The caller's raw access token — lookups happen only on behalf of an authenticated request. */
    private static String callerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new IllegalStateException("A member lookup needs the caller's access token");
    }
}
