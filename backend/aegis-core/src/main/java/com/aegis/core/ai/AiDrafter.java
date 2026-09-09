package com.aegis.core.ai;

/**
 * Port: draft a response (risk ticket, version-upgrade suggestion, or notification memo), each
 * citing its sources. Implemented by a call to the Python ai-service.
 */
public interface AiDrafter {

    AiDtos.Draft draft(AiDtos.DraftRequest request);
}
