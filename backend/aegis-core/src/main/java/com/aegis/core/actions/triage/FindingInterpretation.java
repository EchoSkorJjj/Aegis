package com.aegis.core.actions.triage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The AI's reading of one material finding in one service (V46): a suggestion for a person, never read to score, rank
 * or decide. {@code itemKey} is {@link TriageQueue.Item#key()}, since findings aren't stored; {@code fingerprint} is a
 * hash of what the AI was told, so it is asked again only when that changes.
 */
public record FindingInterpretation(UUID id, UUID serviceId, String itemKey, UUID changeSignalId, String subject,
                                    Signal signal, Confidence confidence, String whatChanged, String whyItMatters,
                                    String whatNext, SuggestedAction suggestedAction, String actionDetail,
                                    String condition, ConditionMet conditionMet, List<String> assumptions, List<String> citations, String fingerprint,
                                    Instant interpretedAt) {

    public enum Signal { UNCERTAIN, LIKELY_REAL, LIKELY_NOISE }

    public enum Confidence { LOW, MEDIUM, HIGH }

    public enum SuggestedAction { VERIFY, ACT_NOW, SCHEDULE, MONITOR }

    /** Whether the sources show that what the change needs holds in this service. */
    public enum ConditionMet { UNKNOWN, YES, NO }

    public FindingInterpretation {
        assumptions = List.copyOf(assumptions);
        citations = List.copyOf(citations);
    }
}
