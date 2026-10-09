package com.aegis.core.platform.identity;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aegis.core.platform.common.ApiExceptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link IdentityInternalApi} over identity-service's {@code /internal/**}, with the shared service token in
 * {@code X-Service-Token}. Its own {@link RestClient}, for the reasons {@link HttpIdentityDirectory} gives, and
 * explicit timeouts so a slow identity-service can't hold up the scheduled job for long.
 */
@Component
class HttpIdentityInternalApi implements IdentityInternalApi {

    private final RestClient http;
    private final ObjectMapper objectMapper;

    HttpIdentityInternalApi(
            @Value("${aegis.identity.base-url:http://localhost:8081}") String baseUrl,
            @Value("${aegis.identity.service-token:}") String serviceToken,
            ObjectMapper objectMapper,
            ObservationRegistry observationRegistry) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .defaultHeader("X-Service-Token", serviceToken)
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<UUID> organizationIds() {
        String body;
        try {
            body = http.get().uri("/internal/organizations").retrieve().body(String.class);
        } catch (RestClientException unreachableOrRefused) {
            throw new ApiExceptions.UpstreamUnavailable("Could not list the organizations with identity-service");
        }
        try {
            return body == null || body.isBlank() ? List.of() : Arrays.asList(objectMapper.readValue(body, UUID[].class));
        } catch (JacksonException malformed) {
            throw new ApiExceptions.UpstreamUnavailable("identity-service returned an unreadable organization list");
        }
    }

    @Override
    public EmailOutcome emailOwner(UUID orgId, UUID userId, List<OwnerEmailItem> items) {
        String request = objectMapper.writeValueAsString(Map.of("orgId", orgId, "userId", userId, "items", items));
        try {
            http.post().uri("/internal/notifications/owner-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            return EmailOutcome.SENT;
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Conflict unreachable) {
            return EmailOutcome.UNREACHABLE;
        } catch (RestClientException unreachableOrFailed) {
            throw new ApiExceptions.UpstreamUnavailable("identity-service did not send the email");
        }
    }
}
