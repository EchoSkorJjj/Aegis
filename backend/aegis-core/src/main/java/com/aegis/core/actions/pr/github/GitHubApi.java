package com.aegis.core.actions.pr.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.aegis.core.platform.common.ApiExceptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Private HTTP boundary: fixed operator-configured origin, no redirects, no raw upstream errors. */
final class GitHubApi {
    static final String API_VERSION = "2026-03-10";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private final RestClient http;
    private final URI baseUrl;
    private final ObjectMapper mapper;

    GitHubApi(URI baseUrl, ObjectMapper mapper, ObservationRegistry observations) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
        var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(30));
        http = RestClient.builder().requestFactory(factory).observationRegistry(observations)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION).build();
    }

    URI uri(String path, String ref) {
        var builder = UriComponentsBuilder.fromUri(baseUrl).pathSegment(path.split("/"));
        if (ref != null) {
            builder.queryParam("ref", ref);
        }
        return builder.build().encode().toUri();
    }

    <T> T call(HttpMethod method, URI uri, String token, Object body, Class<T> type,
               boolean missingAllowed, String notFound) {
        try {
            var request = http.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                if (status == 404) {
                    if (missingAllowed) {
                        return null;
                    }
                    throw new ApiExceptions.NotFound(notFound);
                }
                if (status == 401 || status == 403) {
                    throw new ApiExceptions.UpstreamUnavailable("GitHub authentication or installation permissions were refused");
                }
                if (status == 409 || status == 422) {
                    throw new ApiExceptions.Conflict("GitHub rejected the change; the branch may already exist or the proposal may conflict");
                }
                if (status < 200 || status >= 300) {
                    throw new ApiExceptions.UpstreamUnavailable("GitHub request failed (HTTP " + status + ")");
                }
                if (type == Void.class) {
                    return null;
                }
                try {
                    byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) {
                        throw invalidResponse();
                    }
                    T value = mapper.readValue(bytes, type);
                    if (value == null) {
                        throw invalidResponse();
                    }
                    return value;
                } catch (IOException | JacksonException ex) {
                    // Sanitize inside the exchange: RestClient records callback failures in tracing
                    // before they reach the outer catch. JSON errors can quote response secrets.
                    throw invalidResponse();
                }
            });
        } catch (RestClientException | JacksonException ex) {
            // Never propagate an HTTP/JSON exception containing response bodies, headers or tokens.
            throw new ApiExceptions.UpstreamUnavailable("GitHub request failed or returned an invalid response");
        }
    }

    <T> T get(String path, String token, Class<T> type) {
        return call(HttpMethod.GET, uri(path, null), token, null, type, false, "GitHub repository or resource not found");
    }

    <T> T post(String path, String token, Map<String, ?> body, Class<T> type) {
        return call(HttpMethod.POST, uri(path, null), token, body, type, false, "GitHub repository or resource not found");
    }

    static ApiExceptions.UpstreamUnavailable invalidResponse() {
        return new ApiExceptions.UpstreamUnavailable("GitHub returned an invalid response");
    }
}
