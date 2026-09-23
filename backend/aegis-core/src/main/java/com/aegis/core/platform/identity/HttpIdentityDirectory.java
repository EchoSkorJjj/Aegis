package com.aegis.core.platform.identity;

import java.time.Duration;
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
 * {@link IdentityDirectory} over identity-service's REST API ({@code GET /users/{id}}).
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

    private final RestClient http;
    private final ObjectReader memberReader;

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

    /** The caller's raw access token — lookups happen only on behalf of an authenticated request. */
    private static String callerToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        throw new IllegalStateException("A member lookup needs the caller's access token");
    }
}
