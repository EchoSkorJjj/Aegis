package com.aegis.core.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.aegis.core.ai.AiDrafter;
import com.aegis.core.ai.AiDtos;
import com.aegis.core.platform.common.ApiExceptions;

/**
 * A deterministic stand-in for the ai-service's drafts, so no integration test ever reaches a local
 * ai-service (or spends an LLM budget) and the drafted text can be asserted. Records every request.
 */
public class FakeAiDrafter implements AiDrafter {

    public static final String CONTENT = "Upgrading removes the vulnerable code path.";

    private final List<AiDtos.DraftRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean unavailable;

    /** Make every draft fail, as an ai-service outage would, until reset. */
    public void unavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public List<AiDtos.DraftRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public AiDtos.Draft draft(AiDtos.DraftRequest request) {
        requests.add(request);
        if (unavailable) {
            throw new ApiExceptions.UpstreamUnavailable("The AI service could not complete the request (HTTP 503)");
        }
        return new AiDtos.Draft(request.draftType(), CONTENT, List.of("C1 quoted context"));
    }
}
