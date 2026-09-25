package com.aegis.core.ai;

import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The single implementation of the AI port: calls the Python ai-service over REST ({@code aiRestClient}
 * carries its base URL, the service token and the timeouts).
 *
 * <p>Every failure — an error status, a timeout, a malformed body — becomes an
 * {@link ApiExceptions.UpstreamUnavailable} with a fixed message. The ai-service's own error body is
 * never relayed, and callers treat AI output as an enrichment they can do without.
 */
@Component
public class HttpAiClient implements AiAssessor, AiDrafter {

    private final RestClient ai;

    public HttpAiClient(RestClient aiRestClient) {
        this.ai = aiRestClient;
    }

    @Override
    public AiDtos.Assessment assess(AiDtos.AssessmentRequest request) {
        return post("/v1/assess", request, AiDtos.Assessment.class);
    }

    @Override
    public AiDtos.Draft draft(AiDtos.DraftRequest request) {
        return post("/v1/draft", request, AiDtos.Draft.class);
    }

    private <T> T post(String path, Object request, Class<T> type) {
        T response;
        try {
            response = ai.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (sent, received) -> {
                        throw new ApiExceptions.UpstreamUnavailable(
                                "The AI service could not complete the request (HTTP " + received.getStatusCode().value() + ")");
                    })
                    .body(type);
        } catch (RestClientException unreachableOrMalformed) {
            throw new ApiExceptions.UpstreamUnavailable("The AI service is unreachable or returned an invalid response");
        }
        if (response == null) {
            throw new ApiExceptions.UpstreamUnavailable("The AI service returned an empty response");
        }
        return response;
    }
}
