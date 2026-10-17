package com.aegis.core.ai;

/**
 * Port: whether a service's code uses the vulnerable function, feature or condition of a package it imports (E11-5),
 * in two passes — {@link #lookFor} names what to search the code for, from the advisory and its fix; {@link #assess}
 * answers from that search and a few lines of the code, citing them. A suggestion for a person — it never changes a
 * score or decides anything. Implemented by calls to the Python ai-service.
 */
public interface AiAssessor {

    AiDtos.LookFor lookFor(AiDtos.LookForRequest request);

    AiDtos.Assessment assess(AiDtos.AssessmentRequest request);
}
