package com.aegis.core.actions.memo;

/** A memo's life: drafted and awaiting a decision, approved (final, ready to submit), or dismissed with a reason. */
public enum RegulatorMemoStatus {
    PROPOSED,
    APPROVED,
    DISMISSED
}
