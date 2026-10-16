package com.aegis.core.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.aegis.core.ai.AiAssessor;
import com.aegis.core.ai.AiDtos;

/**
 * A deterministic stand-in for the ai-service's reachability assessment (E11-5), both passes, so no integration test
 * reaches a local ai-service or spends an LLM budget. Records every request — what would have left Aegis — and answers
 * what it is told to, or fails as an outage would.
 */
public class FakeAiAssessor implements AiAssessor {

    public static final AiDtos.Assessment NOT_CALLED = new AiDtos.Assessment("NOT_CALLED",
            "The code takes only merge from the package; the advisory concerns template.", List.of(), List.of("E1"));

    private final List<AiDtos.LookForRequest> lookFors = Collections.synchronizedList(new ArrayList<>());
    private final List<AiDtos.AssessmentRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile AiDtos.Assessment answer = NOT_CALLED;
    private volatile List<String> terms = List.of();
    private volatile RuntimeException failure;
    private volatile RuntimeException lookForFailure;

    /** Answer {@code answer} from now on, name nothing to search for, and stop failing. */
    public void answers(AiDtos.Assessment answer) {
        this.answer = answer;
        this.terms = List.of();
        this.failure = null;
        this.lookForFailure = null;
    }

    /** Name {@code terms} to search the code for (pass 1). */
    public void looksFor(List<String> terms) {
        this.terms = List.copyOf(terms);
    }

    /** Fail both passes with {@code failure} until told to answer: the AI is down. */
    public void fails(RuntimeException failure) {
        this.failure = failure;
    }

    /** Fail pass 1 only. */
    public void failsToLookFor(RuntimeException failure) {
        this.lookForFailure = failure;
    }

    public List<AiDtos.LookForRequest> lookFors() {
        synchronized (lookFors) {
            return List.copyOf(lookFors);
        }
    }

    public List<AiDtos.AssessmentRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public AiDtos.LookFor lookFor(AiDtos.LookForRequest request) {
        lookFors.add(request);
        RuntimeException failing = failure != null ? failure : lookForFailure;
        if (failing != null) {
            throw failing;
        }
        return new AiDtos.LookFor(terms);
    }

    @Override
    public AiDtos.Assessment assess(AiDtos.AssessmentRequest request) {
        requests.add(request);
        RuntimeException failing = failure;
        if (failing != null) {
            throw failing;
        }
        return answer;
    }
}
