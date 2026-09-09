package com.aegis.core.ai;

/**
 * Port: assess a finding in context (signal vs noise, reachability/exposure, suggested severity),
 * grounded with citations. Implemented by a call to the Python ai-service.
 */
public interface AiAssessor {

    AiDtos.Assessment assess(AiDtos.AssessmentRequest request);
}
