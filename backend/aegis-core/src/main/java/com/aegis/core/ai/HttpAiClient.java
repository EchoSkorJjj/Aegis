package com.aegis.core.ai;

import java.time.Duration;

import com.aegis.core.platform.common.ApiExceptions;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>One exception is waited out: a 429 is the ai-service's per-minute budget, full for now, with {@code Retry-After}
 * saying when it has room. Every AI call is someone's request they are waiting on — and an assessment is two calls on
 * that one budget, so the second can find it full — so the call is made once more after that wait, when it is at most
 * {@link #MAX_WAIT}. Otherwise the failure is the {@code (HTTP 429)} it was.
 */
@Component
public class HttpAiClient implements AiAssessor, AiDrafter {

    /** The longest wait for the ai-service's per-minute budget: its rolling minute. */
    static final Duration MAX_WAIT = Duration.ofSeconds(60);

    /** Waits; a seam for tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestClient ai;
    private final Sleeper sleeper;

    @Autowired
    public HttpAiClient(RestClient aiRestClient) {
        this(aiRestClient, duration -> Thread.sleep(duration));
    }

    HttpAiClient(RestClient aiRestClient, Sleeper sleeper) {
        this.ai = aiRestClient;
        this.sleeper = sleeper;
    }

    @Override
    public AiDtos.LookFor lookFor(AiDtos.LookForRequest request) {
        return post("/v1/assess/look-for", request, AiDtos.LookFor.class);
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
        try {
            return call(path, request, type);
        } catch (Full full) {
            if (full.retryAfter == null || full.retryAfter.compareTo(MAX_WAIT) > 0) {
                throw full.unavailable();
            }
            try {
                sleeper.sleep(full.retryAfter);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw full.unavailable();
            }
            try {
                return call(path, request, type);
            } catch (Full stillFull) {
                throw stillFull.unavailable();
            }
        }
    }

    private <T> T call(String path, Object request, Class<T> type) {
        T response;
        try {
            response = ai.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (sent, received) -> {
                        if (received.getStatusCode().value() == 429) {
                            throw new Full(received.getHeaders().getFirst("Retry-After"));
                        }
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

    /** The ai-service's per-minute budget is full; {@code retryAfter} is when it has room, or null if it didn't say. */
    private static final class Full extends RuntimeException {
        private final Duration retryAfter;

        Full(String retryAfter) {
            super(null, null, false, false);
            Duration seconds = null;
            try {
                long value = Long.parseLong(retryAfter == null ? "" : retryAfter.strip());
                seconds = value > 0 ? Duration.ofSeconds(value) : null;
            } catch (NumberFormatException notSeconds) {
                // An HTTP date or nothing: no wait to rely on.
            }
            this.retryAfter = seconds;
        }

        ApiExceptions.UpstreamUnavailable unavailable() {
            return new ApiExceptions.UpstreamUnavailable("The AI service could not complete the request (HTTP 429)");
        }
    }
}
