package com.aegis.core.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.aegis.core.common.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Temporary connectivity endpoints under {@code /api} — both require a valid JWT (enforced at the
 * gateway <i>and</i> here), so they demonstrate the two access rules end to end:
 *
 * <ul>
 *   <li>{@code GET /api/ping} — reachable only by an authenticated caller.</li>
 *   <li>{@code GET /api/ai/ping} — aegis-core (and only aegis-core) reaching the internal ai-service,
 *       using the service token carried by {@code aiRestClient}.</li>
 * </ul>
 *
 * Replace with real domain endpoints as features land (BUILD_PLAN step 1+).
 */
@RestController
@RequestMapping("/api")
public class PingController {

    private static final Logger log = LoggerFactory.getLogger(PingController.class);

    private final RestClient aiRestClient;

    public PingController(RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "aegis-core");
        body.put("status", "ok");
        body.put("user", jwt != null ? jwt.getSubject() : null);
        return body;
    }

    @GetMapping("/ai/ping")
    public Map<String, Object> aiPing() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> upstream = aiRestClient.get()
                    .uri("/v1/ping")
                    .retrieve()
                    .body(Map.class);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("via", "aegis-core");
            body.put("aiService", upstream);
            return body;
        } catch (RestClientException ex) {
            // ai-service unreachable, or rejected our service token. Log the detail server-side;
            // never leak upstream internals (URLs, error messages) to the caller.
            log.warn("ai-service call to /v1/ping failed", ex);
            throw new ApiExceptions.UpstreamUnavailable("ai-service is unavailable");
        }
    }
}
