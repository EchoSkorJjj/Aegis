package com.aegis.core.ai;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The single implementation of the AI port: calls the Python ai-service over REST.
 *
 * <p><b>Scaffold:</b> the client is fully wired ({@code aiRestClient} carries the base URL and
 * service token), but the two calls are not implemented yet — that is BUILD_PLAN.md step 7. When
 * implemented, each method POSTs to the ai-service (e.g. {@code /v1/assess}) and maps the response.
 */
@Component
public class HttpAiClient implements AiAssessor, AiDrafter {

    @SuppressWarnings("unused") // wired now; used once the calls below are implemented (step 7)
    private final RestClient ai;

    public HttpAiClient(RestClient aiRestClient) {
        this.ai = aiRestClient;
    }

    @Override
    public AiDtos.Assessment assess(AiDtos.AssessmentRequest request) {
        // TODO(step 7): return ai.post().uri("/v1/assess").body(request).retrieve().body(AiDtos.Assessment.class);
        throw new UnsupportedOperationException("ai-service assess not implemented yet (BUILD_PLAN step 7)");
    }

    @Override
    public AiDtos.Draft draft(AiDtos.DraftRequest request) {
        // TODO(step 7): return ai.post().uri("/v1/draft").body(request).retrieve().body(AiDtos.Draft.class);
        throw new UnsupportedOperationException("ai-service draft not implemented yet (BUILD_PLAN step 7)");
    }
}
