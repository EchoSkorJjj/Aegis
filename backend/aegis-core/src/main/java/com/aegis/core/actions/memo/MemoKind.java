package com.aegis.core.actions.memo;

/** Which DORA notification a memo drafts, from the kind of change (Article 19). */
public enum MemoKind {
    /** A vendor outage: the initial notification of a major ICT-related incident (Article 19(1)). */
    MAJOR_INCIDENT("dora-major-incident.md"),
    /** A vulnerability: the voluntary notification of a significant cyber threat (Article 19(2)). */
    CYBER_THREAT("dora-cyber-threat.md");

    private final String template;

    MemoKind(String template) {
        this.template = template;
    }

    String template() {
        return template;
    }
}
