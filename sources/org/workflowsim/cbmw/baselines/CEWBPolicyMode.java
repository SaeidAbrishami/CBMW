package org.workflowsim.cbmw.baselines;

/** Selects the broker policy while keeping the CEWB spot market unchanged. */
public enum CEWBPolicyMode {
    /** Existing paper-aligned policy: HEFT timing and normalized criticality. */
    CURRENT,

    /** Reference PCP timing and absolute slack classes with current recovery. */
    REFERENCE_POLICY,

    /** Reference policy plus partial-progress recovery after interruption. */
    REFERENCE_ADAPTED,

    /** Historical safe-start/attempt-limit reconstruction. */
    RECONSTRUCTED;

    boolean usesTaskPolicy() {
        return this != RECONSTRUCTED;
    }

    boolean usesReferenceTaskPolicy() {
        return this == REFERENCE_POLICY || this == REFERENCE_ADAPTED;
    }

    boolean usesReferenceRecovery() {
        return this == REFERENCE_ADAPTED;
    }

    boolean usesPaperPricing() {
        return this != RECONSTRUCTED;
    }
}
